package com.sos.jobscheduler.core.event.journal.recover

import akka.pattern.ask
import com.sos.jobscheduler.base.problem.Checked._
import com.sos.jobscheduler.common.scalautil.AutoClosing.autoClosing
import com.sos.jobscheduler.common.scalautil.Futures.implicits._
import com.sos.jobscheduler.common.time.ScalaTime._
import com.sos.jobscheduler.core.event.journal.JournalActor
import com.sos.jobscheduler.core.event.journal.files.JournalFiles
import com.sos.jobscheduler.core.event.journal.recover.JournalRecovererReader._
import com.sos.jobscheduler.core.event.journal.test.{TestActor, TestAggregate, TestAggregateActor, TestEvent, TestJournalMixin}
import com.sos.jobscheduler.core.event.journal.write.SnapshotJournalWriter
import com.sos.jobscheduler.data.event.Stamped
import java.nio.file.Files.delete
import org.scalatest.FreeSpec

/**
  * @author Joacim Zschimmer
  */
final class JournalRecovererReaderTest extends FreeSpec with TestJournalMixin {

  "Journal without snapshots or events" in {
    withTestActor { (_, _ ) ⇒ }
    val file = currentFile
    autoClosing(new JournalRecovererReader(journalMeta, file)) { journalReader ⇒
      assert(journalReader.recoverNext() == Some(AllSnapshotsRecovered))
      assert(!journalReader.isCompletelyRead)
      assert(journalReader.recoverNext() == None)
      assert(journalReader.isCompletelyRead)
    }
  }

  "Journal with snapshot section only" in {
    val file = currentFile
    delete(file)  // File of last test
    autoClosing(new SnapshotJournalWriter(journalMeta, file, observer = None, simulateSync = None, after = 0)) { writer ⇒
      writer.beginSnapshotSection()
    }
    autoClosing(new JournalRecovererReader(journalMeta, JournalFiles.currentFile(journalMeta.fileBase).orThrow)) { journalReader ⇒
      assert(journalReader.recoverNext() == Some(AllSnapshotsRecovered))
      assert(!journalReader.isCompletelyRead)
      assert(journalReader.recoverNext() == None)
      assert(journalReader.isCompletelyRead)
    }
  }

  "Test" in {
    withTestActor { (actorSystem, actor) ⇒
      for ((key, cmd) ← testCommands("TEST")) execute(actorSystem, actor, key, cmd) await 99.s
      (actor ? TestActor.Input.TakeSnapshot).mapTo[JournalActor.Output.SnapshotTaken.type] await 99.s
      execute(actorSystem, actor, "X", TestAggregateActor.Command.Add("(X)")) await 99.s
      execute(actorSystem, actor, "Y", TestAggregateActor.Command.Add("(Y)")) await 99.s
    }
    autoClosing(new JournalRecovererReader(journalMeta, currentFile)) { journalReader ⇒
      assert(Vector.fill(2) { journalReader.recoverNext() } .toSet == Set(
        Some(RecoveredSnapshot(TestAggregate("TEST-A","(A.Add)(A.Append)(A.AppendAsync)(A.AppendNested)(A.AppendNestedAsync)"))),
        Some(RecoveredSnapshot(TestAggregate("TEST-C","(C.Add)")))))
      assert(journalReader.recoverNext() == Some(AllSnapshotsRecovered))
      assert(journalReader.recoverNext() == Some(RecoveredEvent(Stamped(1000066, "X" <-: TestEvent.Added("(X)")))))
      assert(journalReader.recoverNext() == Some(RecoveredEvent(Stamped(1000067, "Y" <-: TestEvent.Added("(Y)")))))
      assert(!journalReader.isCompletelyRead)
      assert(journalReader.recoverNext() == None)
      assert(journalReader.isCompletelyRead)
    }
  }

  private def currentFile = JournalFiles.currentFile(journalMeta.fileBase).orThrow
}
