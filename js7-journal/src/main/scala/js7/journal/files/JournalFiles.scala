package js7.journal.files

import java.io.IOException
import java.nio.file.Files.{createSymbolicLink, delete, exists}
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.{Files, Path, Paths}
import js7.base.io.file.FileUtils.touchFile
import js7.base.log.Logger
import js7.base.problem.Checked._
import js7.base.problem.{Checked, Problem}
import js7.base.utils.Assertions.assertThat
import js7.base.utils.AutoClosing.autoClosing
import js7.base.utils.ScalaUtils.syntax.RichThrowable
import js7.data.event.EventId
import js7.journal.data.JournalMeta
import scala.jdk.CollectionConverters._
import scala.util.Try

/**
  * @author Joacim Zschimmer
  */
object JournalFiles
{
  private val logger = Logger[this.type]

  def currentFile(journalFileBase: Path): Checked[Path] =
    listJournalFiles(journalFileBase).lastOption.map(_.file) toChecked Problem(s"No journal under '$journalFileBase'")

  def listJournalFiles(journalFileBase: Path): Vector[JournalFile] =
    listFiles(journalFileBase) { iterator =>
      val matcher = new JournalFile.Matcher(journalFileBase.getFileName)
      iterator
        .flatMap(file => matcher.checkedJournalFile(file).toOption)
        .toVector.sortBy(_.afterEventId)
    }

  def listGarbageFiles(journalFileBase: Path, untilEventId: EventId): Vector[Path] =
    listFiles(journalFileBase) { iterator =>
      val pattern = JournalFile.garbagePattern(journalFileBase.getFileName)
      iterator.filter { file =>
        val matcher = pattern.matcher(file.getFileName.toString)
        matcher.matches() &&
          Try(matcher.group(1).toLong < untilEventId).getOrElse(false)
      }
      .toVector
      .sorted
    }

  private def listFiles[A](journalFileBase: Path)(body: Iterator[Path] => Vector[A]): Vector[A] = {
    val directory = journalFileBase.getParent
    if (!exists(directory))
      Vector.empty
    else if (journalFileBase.getFileName == null)
      Vector.empty
    else
      autoClosing(Files.list(directory)) { stream =>
        body(stream.iterator.asScala)
      }
  }

  private[files] def deleteJournalIfMarked(fileBase: Path): Checked[Unit] =
    try {
      val markerFile = deletionMarkerFile(fileBase)
      if (exists(markerFile)) {
        logger.warn("DELETE JOURNAL DUE TO AGENT RESET IN PREVIOUS RUN" +
          s" ($markerFile marker file found")
        deleteJournal(fileBase)
      }
      Checked.unit
    } catch { case e: IOException =>
      Left(Problem.pure(e.toStringWithCauses))
    }

  private[files] def deleteJournal(fileBase: Path, ignoreFailure: Boolean = false): Unit = {
    val matches = JournalFile.anyJournalFilePattern(fileBase.getFileName).matcher(_: String).matches
    val markerFile = deletionMarkerFile(fileBase)
    if (!exists(markerFile)/*required for test*/) touchFile(markerFile)
    var failed = false
    for (file <- listFiles(fileBase)(_.filter(file => matches(file.getFileName.toString)).toVector)) {
      try {
        logger.info(s"DELETE JOURNAL FILE: $file")
        delete(file)
      } catch { case e: IOException if ignoreFailure =>
        scribe.error(s"Delete journal file: $file => $e")
        failed = true
      }
    }
    if (failed) {
      scribe.warn("Journal files will be deleted at next start of Agent")
    } else {
      delete(markerFile)
    }
  }

  private[files] def deletionMarkerFile(fileBase: Path): Path =
    Paths.get(s"$fileBase-DELETE!")

  implicit final class JournalMetaOps(private val journalMeta: JournalMeta) extends AnyVal
  {
    def file(after: EventId): Path =
      JournalFile.toFile(journalMeta.fileBase, after)

    def updateSymbolicLink(toFile: Path): Unit = {
      val symLink = Paths.get(s"${journalMeta.fileBase}-journal")  // We preserve the suffix ".journal" for the real journal files
      Try { if (exists(symLink, NOFOLLOW_LINKS)) delete(symLink) }
      Try { createSymbolicLink(symLink, toFile.getFileName) }
    }

    def deleteJournalIfMarked(): Checked[Unit] =
      JournalFiles.deleteJournalIfMarked(journalMeta.fileBase)

    def deleteJournal(ignoreFailure: Boolean = false): Unit =
      JournalFiles.deleteJournal(journalMeta.fileBase, ignoreFailure)
  }

  def updateSymbolicLink(fileBase: Path, toFile: Path): Unit = {
    assertThat(toFile.toString startsWith fileBase.toString)
    val symLink = Paths.get(s"$fileBase-journal")  // We preserve the suffix ".journal" for the real journal files
    Try { if (exists(symLink, NOFOLLOW_LINKS)) delete(symLink) }
    Try { createSymbolicLink(symLink, toFile.getFileName) }
  }
}
