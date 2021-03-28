package js7.data.workflow.instructions

import io.circe.generic.semiauto.deriveCodec
import js7.data.source.SourcePos
import js7.data.workflow.Instruction

/**
  * @author Joacim Zschimmer
  */
final case class Retry(sourcePos: Option[SourcePos] = None)
extends Instruction
{
  def withoutSourcePos = copy(sourcePos = None)

  override def toString = "retry" + sourcePosToString
}

object Retry {
  implicit val jsonCodec = deriveCodec[Retry]
}
