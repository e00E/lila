package lila.study

import akka.actor.{ ActorRef, ActorSelection }
import scala.concurrent.duration._

import chess.format.pgn.Glyph
import lila.hub.actorApi.map.Tell
import lila.hub.actorApi.timeline.{ Propagate, StudyCreate, StudyLike }
import lila.hub.Sequencer
import lila.socket.Socket.Uid
import lila.tree.Node.{ Shapes, Comment }
import lila.user.{ User, UserRepo }

final class StudyApi(
    studyRepo: StudyRepo,
    chapterRepo: ChapterRepo,
    sequencers: ActorRef,
    studyMaker: StudyMaker,
    chapterMaker: ChapterMaker,
    notifier: StudyNotifier,
    tagsFixer: ChapterTagsFixer,
    lightUser: lila.common.LightUser.GetterSync,
    scheduler: akka.actor.Scheduler,
    chat: ActorSelection,
    bus: lila.common.Bus,
    timeline: ActorSelection,
    socketHub: ActorRef) {

  def byId = studyRepo byId _

  def byIds = studyRepo byOrderedIds _

  def publicByIds(ids: Seq[Study.Id]) = byIds(ids) map { _.filter(_.isPublic) }

  private def fetchAndFixChapter(id: Chapter.Id): Fu[Option[Chapter]] =
    chapterRepo.byId(id) flatMap {
      _ ?? { c => tagsFixer(c) map some }
    }

  def byIdWithChapter(id: Study.Id): Fu[Option[Study.WithChapter]] = byId(id) flatMap {
    _ ?? { study =>
      fetchAndFixChapter(study.position.chapterId) flatMap {
        case None => chapterRepo.firstByStudy(study.id) flatMap {
          case None => fuccess(none)
          case Some(chapter) =>
            val fixed = study withChapter chapter
            studyRepo.updateSomeFields(fixed) inject
              Study.WithChapter(fixed, chapter).some
        }
        case Some(chapter) => fuccess(Study.WithChapter(study, chapter).some)
      }
    }
  }

  def byIdWithChapter(id: Study.Id, chapterId: Chapter.Id): Fu[Option[Study.WithChapter]] = byId(id) flatMap {
    _ ?? { study =>
      fetchAndFixChapter(chapterId) map {
        _.filter(_.studyId == study.id) map { Study.WithChapter(study, _) }
      }
    }
  }

  def byIdWithFirstChapter(id: Study.Id): Fu[Option[Study.WithChapter]] = byId(id) flatMap {
    _ ?? { study =>
      chapterRepo.firstByStudy(study.id) map {
        _ ?? { Study.WithChapter(study, _).some }
      }
    }
  }

  def create(data: DataForm.Data, user: User): Fu[Study.WithChapter] =
    studyMaker(data, user) flatMap { res =>
      studyRepo.insert(res.study) >>
        chapterRepo.insert(res.chapter) >>-
        indexStudy(res.study) >>-
        scheduleTimeline(res.study.id) inject res
    }

  def clone(me: User, prev: Study): Fu[Option[Study]] =
    Settings.UserSelection.allows(prev.settings.cloneable, prev, me.id.some) ?? {
      chapterRepo.orderedByStudy(prev.id).flatMap { chapters =>
        val study1 = prev.cloneFor(me)
        val newChapters = chapters.map(_ cloneFor study1)
        newChapters.headOption.map(study1.rewindTo) ?? { study =>
          studyRepo.insert(study) >>
            newChapters.map(chapterRepo.insert).sequenceFu >>- {
              chat ! lila.chat.actorApi.SystemTalk(
                study.id.value,
                s"Cloned from lichess.org/study/${prev.id}")
            } inject study.some
        }
      }
    }

  def resetIfOld(study: Study, chapters: List[Chapter.Metadata]): Fu[Study] =
    chapters.headOption match {
      case Some(c) if study.isOld && study.position != c.initialPosition =>
        val newStudy = study rewindTo c
        studyRepo.updateSomeFields(newStudy) inject newStudy
      case _ => fuccess(study)
    }

  private def scheduleTimeline(studyId: Study.Id) = scheduler.scheduleOnce(1 minute) {
    byId(studyId) foreach {
      _.filter(_.isPublic) foreach { study =>
        timeline ! (Propagate(StudyCreate(study.ownerId, study.id.value, study.name.value)) toFollowersOf study.ownerId)
      }
    }
  }

  def talk(userId: User.ID, studyId: Study.Id, text: String, socket: ActorRef) = byId(studyId) foreach {
    _ foreach { study =>
      (study canChat userId) ?? {
        chat ! lila.chat.actorApi.UserTalk(studyId.value, userId, text)
      }
    }
  }

  def setPath(userId: User.ID, studyId: Study.Id, position: Position.Ref, uid: Uid) = sequenceStudy(studyId) { study =>
    Contribute(userId, study) {
      chapterRepo.byId(position.chapterId).map {
        _ filter { c =>
          c.root.pathExists(position.path) && study.position.chapterId == c.id
        }
      } flatMap {
        case None => funit >>- reloadUid(study, uid)
        case Some(chapter) if study.position.path != position.path =>
          studyRepo.setPosition(study.id, position) >>
            updateConceal(study, chapter, position) >>-
            sendTo(study, Socket.SetPath(position, uid))
        case _ => funit
      }
    }
  }

  def addNode(userId: User.ID, studyId: Study.Id, position: Position.Ref, node: Node, uid: Uid) = sequenceStudyWithChapter(studyId) {
    case Study.WithChapter(study, chapter) => Contribute(userId, study) {
      chapter.addNode(node, position.path) match {
        case None => fufail(s"Invalid addNode $studyId $position $node") >>- reloadUid(study, uid)
        case Some(newChapter) =>
          chapterRepo.update(newChapter) >>
            studyRepo.setPosition(study.id, position + node) >>
            updateConceal(study, newChapter, position + node) >>-
            sendTo(study, Socket.AddNode(position, node, uid))
      }
    }
  }

  private def updateConceal(study: Study, chapter: Chapter, position: Position.Ref) =
    chapter.conceal ?? { conceal =>
      chapter.root.lastMainlinePlyOf(position.path).some.filter(_ > conceal) ?? { newConceal =>
        if (newConceal >= chapter.root.lastMainlinePly)
          chapterRepo.removeConceal(chapter.id) >>-
            sendTo(study, Socket.SetConceal(position, none))
        else
          chapterRepo.setConceal(chapter.id, newConceal) >>-
            sendTo(study, Socket.SetConceal(position, newConceal.some))
      }
    }

  def deleteNodeAt(userId: User.ID, studyId: Study.Id, position: Position.Ref, uid: Uid) = sequenceStudyWithChapter(studyId) {
    case Study.WithChapter(study, chapter) => Contribute(userId, study) {
      chapter.updateRoot { root =>
        root.withChildren(_.deleteNodeAt(position.path))
      } match {
        case Some(newChapter) =>
          chapterRepo.update(newChapter) >>-
            sendTo(study, Socket.DeleteNode(position, uid))
        case None => fufail(s"Invalid delNode $studyId $position") >>- reloadUid(study, uid)
      }
    }
  }

  def promote(userId: User.ID, studyId: Study.Id, position: Position.Ref, toMainline: Boolean, uid: Uid) = sequenceStudyWithChapter(studyId) {
    case Study.WithChapter(study, chapter) => Contribute(userId, study) {
      chapter.updateRoot { root =>
        root.withChildren { children =>
          if (toMainline) children.promoteToMainlineAt(position.path)
          else children.promoteUpAt(position.path).map(_._1)
        }
      } match {
        case Some(newChapter) =>
          chapterRepo.update(newChapter) >>-
            sendTo(study, Socket.Promote(position, toMainline, uid))
        case None => fufail(s"Invalid promoteToMainline $studyId $position") >>- reloadUid(study, uid)
      }
    }
  }

  def setRole(byUserId: User.ID, studyId: Study.Id, userId: User.ID, roleStr: String) = sequenceStudy(studyId) { study =>
    (study isOwner byUserId) ?? {
      val role = StudyMember.Role.byId.getOrElse(roleStr, StudyMember.Role.Read)
      studyRepo.setRole(study, userId, role) >>- reloadMembers(study)
    }
  }

  def invite(byUserId: User.ID, studyId: Study.Id, username: String, socket: ActorRef) = sequenceStudy(studyId) { study =>
    (study.isOwner(byUserId) && study.nbMembers < 30) ?? {
      UserRepo.named(username).flatMap {
        _.filterNot(study.members.contains) ?? { user =>
          studyRepo.addMember(study, StudyMember make user) >>-
            notifier.invite(study, user, socket)
        }
      } >>- reloadMembers(study) >>- indexStudy(study)
    }
  }

  def kick(studyId: Study.Id, userId: User.ID) = sequenceStudy(studyId) { study =>
    study.isMember(userId) ?? {
      studyRepo.removeMember(study, userId)
    } >>- reloadMembers(study) >>- indexStudy(study)
  }

  def setShapes(userId: User.ID, studyId: Study.Id, position: Position.Ref, shapes: Shapes, uid: Uid) = sequenceStudy(studyId) { study =>
    Contribute(userId, study) {
      chapterRepo.byIdAndStudy(position.chapterId, study.id) flatMap {
        _ ?? { chapter =>
          chapter.setShapes(shapes, position.path) match {
            case Some(newChapter) =>
              studyRepo.updateNow(study)
              chapterRepo.update(newChapter) >>-
                sendTo(study, Socket.SetShapes(position, shapes, uid))
            case None => fufail(s"Invalid setShapes $position $shapes") >>- reloadUid(study, uid)
          }
        }
      }
    }
  }

  def setTag(userId: User.ID, studyId: Study.Id, setTag: actorApi.SetTag, uid: Uid) = sequenceStudy(studyId) { study =>
    Contribute(userId, study) {
      chapterRepo.byIdAndStudy(setTag.chapterId, studyId) flatMap {
        _ ?? { oldChapter =>
          val chapter = oldChapter.setTag(setTag.tag)
          chapterRepo.setTagsFor(chapter) >>-
            sendTo(study, Socket.SetTags(chapter.id, chapter.tags, uid))
        } >>- indexStudy(study)
      }
    }
  }

  def setComment(userId: User.ID, studyId: Study.Id, position: Position.Ref, text: Comment.Text, uid: Uid) = sequenceStudyWithChapter(studyId) {
    case Study.WithChapter(study, chapter) => Contribute(userId, study) {
      (study.members get userId) ?? { byMember =>
        lightUser(userId) ?? { author =>
          val comment = Comment(
            id = Comment.Id.make,
            text = text,
            by = Comment.Author.User(author.id, author.titleName))
          chapter.setComment(comment, position.path) match {
            case Some(newChapter) =>
              studyRepo.updateNow(study)
              newChapter.root.nodeAt(position.path).flatMap(_.comments findBy comment.by) ?? { c =>
                chapterRepo.update(newChapter) >>-
                  sendTo(study, Socket.SetComment(position, c, uid)) >>-
                  indexStudy(study)
              }
            case None => fufail(s"Invalid setComment $studyId $position") >>- reloadUid(study, uid)
          }
        }
      }
    }
  }

  def deleteComment(userId: User.ID, studyId: Study.Id, position: Position.Ref, id: Comment.Id, uid: Uid) = sequenceStudyWithChapter(studyId) {
    case Study.WithChapter(study, chapter) => Contribute(userId, study) {
      (study.members get userId) ?? { byMember =>
        chapter.deleteComment(id, position.path) match {
          case Some(newChapter) =>
            chapterRepo.update(newChapter) >>-
              sendTo(study, Socket.DeleteComment(position, id, uid)) >>-
              indexStudy(study)
          case None => fufail(s"Invalid deleteComment $studyId $position $id") >>- reloadUid(study, uid)
        }
      }
    }
  }

  def toggleGlyph(userId: User.ID, studyId: Study.Id, position: Position.Ref, glyph: Glyph, uid: Uid) = sequenceStudyWithChapter(studyId) {
    case Study.WithChapter(study, chapter) => Contribute(userId, study) {
      (study.members get userId) ?? { byMember =>
        chapter.toggleGlyph(glyph, position.path) match {
          case Some(newChapter) =>
            studyRepo.updateNow(study)
            chapterRepo.update(newChapter) >>-
              newChapter.root.nodeAt(position.path).foreach { node =>
                sendTo(study, Socket.SetGlyphs(position, node.glyphs, uid))
              }
          case None => fufail(s"Invalid toggleGlyph $studyId $position $glyph") >>- reloadUid(study, uid)
        }
      }
    }
  }

  def addChapter(byUserId: User.ID, studyId: Study.Id, data: ChapterMaker.Data, socket: ActorRef, uid: Uid) = sequenceStudy(studyId) { study =>
    Contribute(byUserId, study) {
      chapterRepo.nextOrderByStudy(study.id) flatMap { order =>
        chapterMaker(study, data, order, byUserId) flatMap {
          _ ?? { chapter =>
            data.initial ?? {
              chapterRepo.firstByStudy(study.id) flatMap {
                _.filter(_.isEmptyInitial) ?? chapterRepo.delete
              }
            } >> chapterRepo.insert(chapter) >>
              doSetChapter(study, chapter.id, socket, uid) >>-
              studyRepo.updateNow(study) >>-
              indexStudy(study)
          }
        }
      }
    }
  }

  def setChapter(byUserId: User.ID, studyId: Study.Id, chapterId: Chapter.Id, socket: ActorRef, uid: Uid) = sequenceStudy(studyId) { study =>
    study.canContribute(byUserId) ?? doSetChapter(study, chapterId, socket, uid)
  }

  private def doSetChapter(study: Study, chapterId: Chapter.Id, socket: ActorRef, uid: Uid) =
    (study.position.chapterId != chapterId) ?? {
      chapterRepo.byIdAndStudy(chapterId, study.id) flatMap {
        _ ?? { chapter =>
          studyRepo.updateSomeFields(study withChapter chapter) >>-
            sendTo(study, Socket.ChangeChapter(uid))
        }
      }
    }

  def editChapter(byUserId: User.ID, studyId: Study.Id, data: ChapterMaker.EditData, socket: ActorRef, uid: Uid) = sequenceStudy(studyId) { study =>
    Contribute(byUserId, study) {
      chapterRepo.byIdAndStudy(data.id, studyId) flatMap {
        _ ?? { chapter =>
          val name = Chapter fixName data.name
          val newChapter = chapter.copy(
            name = name,
            practice = data.isPractice option true,
            conceal = (chapter.conceal, data.isConceal) match {
              case (None, true)     => Chapter.Ply(chapter.root.ply).some
              case (Some(_), false) => None
              case _                => chapter.conceal
            },
            setup = chapter.setup.copy(orientation = data.realOrientation))
          if (chapter == newChapter) funit
          else chapterRepo.update(newChapter) >> {
            if (chapter.conceal != newChapter.conceal) {
              (newChapter.conceal.isDefined && study.position.chapterId == chapter.id).?? {
                val newPosition = study.position.withPath(Path.root)
                studyRepo.setPosition(study.id, newPosition)
              } >>-
                sendTo(study, Socket.ReloadAll)
            }
            else fuccess {
              val shouldReload =
                (newChapter.setup.orientation != chapter.setup.orientation) ||
                  (newChapter.practice != chapter.practice)
              if (study.position.chapterId == chapter.id && shouldReload)
                sendTo(study, Socket.ChangeChapter(uid))
              else
                reloadChapters(study)
            }
          }
        } >>- indexStudy(study)
      }
    }
  }

  def deleteChapter(byUserId: User.ID, studyId: Study.Id, chapterId: Chapter.Id, socket: ActorRef, uid: Uid) = sequenceStudy(studyId) { study =>
    Contribute(byUserId, study) {
      chapterRepo.byIdAndStudy(chapterId, studyId) flatMap {
        _ ?? { chapter =>
          chapterRepo.orderedMetadataByStudy(studyId).flatMap {
            case chaps if chaps.size > 1 => (study.position.chapterId == chapterId).?? {
              chaps.find(_.id != chapterId) ?? { newChap =>
                doSetChapter(study, newChap.id, socket, uid)
              }
            } >> chapterRepo.delete(chapter.id)
            case _ => funit
          } >>- reloadChapters(study)
        } >>- indexStudy(study)
      }
    }
  }

  def sortChapters(byUserId: User.ID, studyId: Study.Id, chapterIds: List[Chapter.Id], socket: ActorRef, uid: Uid) = sequenceStudy(studyId) { study =>
    Contribute(byUserId, study) {
      chapterRepo.sort(study, chapterIds) >>- reloadChapters(study)
    }
  }

  def editStudy(studyId: Study.Id, data: Study.Data) = sequenceStudy(studyId) { study =>
    data.settings ?? { settings =>
      val newStudy = study.copy(
        name = Study toName data.name,
        settings = settings,
        visibility = data.vis)
      (newStudy != study) ?? {
        studyRepo.updateSomeFields(newStudy) >>-
          sendTo(study, Socket.ReloadAll) >>-
          indexStudy(study)
      }
    }
  }

  def delete(study: Study) = sequenceStudy(study.id) { study =>
    studyRepo.delete(study) >>
      chapterRepo.deleteByStudy(study) >>-
      bus.publish(actorApi.RemoveStudy(study.id), 'study)
  }

  def like(studyId: Study.Id, userId: User.ID, v: Boolean, socket: ActorRef, uid: Uid): Funit =
    studyRepo.like(studyId, userId, v) map { likes =>
      sendTo(studyId, Socket.SetLiking(Study.Liking(likes, v), uid))
      if (v) studyRepo byId studyId foreach {
        _ foreach { study =>
          if (userId != study.ownerId)
            timeline ! (Propagate(StudyLike(userId, study.id.value, study.name.value)) toFollowersOf userId)
        }
      }
    }

  def resetAllRanks = studyRepo.resetAllRanks

  def chapterIdNames(studyIds: List[Study.Id]): Fu[Map[Study.Id, Vector[Chapter.IdName]]] =
    chapterRepo.idNamesByStudyIds(studyIds)

  def chapterMetadatas = chapterRepo.orderedMetadataByStudy _

  private def indexStudy(study: Study) =
    bus.publish(actorApi.SaveStudy(study), 'study)

  private def reloadUid(study: Study, uid: Uid) =
    sendTo(study, Socket.ReloadUid(uid))

  private def reloadMembers(study: Study) =
    studyRepo.membersById(study.id).foreach {
      _ foreach { members =>
        sendTo(study, Socket.ReloadMembers(members))
      }
    }

  private def reloadChapters(study: Study) =
    chapterRepo.orderedMetadataByStudy(study.id).foreach { chapters =>
      sendTo(study, Socket.ReloadChapters(chapters))
    }

  private def sequenceStudy(studyId: Study.Id)(f: Study => Funit): Funit =
    byId(studyId) flatMap {
      _ ?? { study =>
        sequence(studyId)(f(study))
      }
    }

  private def sequenceStudyWithChapter(studyId: Study.Id)(f: Study.WithChapter => Funit): Funit =
    sequenceStudy(studyId) { study =>
      chapterRepo.byId(study.position.chapterId) flatMap {
        _ ?? { chapter =>
          f(Study.WithChapter(study, chapter))
        }
      }
    }

  private def sequence(studyId: Study.Id)(f: => Funit): Funit = {
    val promise = scala.concurrent.Promise[Unit]
    val work = Sequencer.work(f, promise.some)
    sequencers ! Tell(studyId.value, work)
    promise.future
  }

  import ornicar.scalalib.Zero
  private def Contribute[A](userId: User.ID, study: Study)(f: => A)(implicit default: Zero[A]): A =
    if (study canContribute userId) f else default.zero

  private def sendTo(study: Study, msg: Any): Unit = sendTo(study.id, msg)

  private def sendTo(studyId: Study.Id, msg: Any): Unit =
    socketHub ! Tell(studyId.value, msg)
}
