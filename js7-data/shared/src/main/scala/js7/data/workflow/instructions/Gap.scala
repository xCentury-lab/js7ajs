package js7.data.workflow.instructions

import io.circe.generic.JsonCodec
import js7.data.source.SourcePos
import js7.data.workflow.Instruction

/** reduceForAgent uses Gap for all instructions not executable on the requested Agent.
  * @author Joacim Zschimmer
  */
@JsonCodec
final case class Gap(sourcePos: Option[SourcePos] = None)
extends Instruction
{
  def withoutSourcePos = copy(sourcePos = None)

  override def toString = "gap"
}
