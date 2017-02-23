package com.sos.jobscheduler.taskserver.moduleapi

/**
  * Defines an implementation for JobScheduler configuration &lt;script>.
  *
  * @author Joacim Zschimmer
  */
trait ModuleFactory {

  def toModuleArguments: PartialFunction[RawModuleArguments, ModuleArguments]

  def newModule(arguments: ModuleArguments): Module
}
