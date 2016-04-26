package com.sos.scheduler.engine.taskserver.module

/**
  * Defines an implementation for JobScheduler configuration &lt;script>.
  *
  * @author Joacim Zschimmer
  */
trait ModuleType {

  def toModuleArguments: PartialFunction[RawModuleArguments, ModuleArguments]

  def newModule(arguments: ModuleArguments): Module
}
