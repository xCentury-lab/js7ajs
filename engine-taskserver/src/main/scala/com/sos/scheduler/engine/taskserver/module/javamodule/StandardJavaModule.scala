package com.sos.scheduler.engine.taskserver.module.javamodule

import com.sos.scheduler.engine.taskserver.module.{JavaModuleLanguage, ModuleArguments, ModuleFactory, RawModuleArguments}

/**
 * @author Joacim Zschimmer
 */
final case class StandardJavaModule(arguments: StandardJavaModule.Arguments) extends JavaClassModule {

  private lazy val clazz = Class.forName(arguments.className)

  protected def newInstance() = clazz.newInstance()
}

object StandardJavaModule extends ModuleFactory {
  def toModuleArguments = {
    case args @ RawModuleArguments(JavaModuleLanguage, javaClassNameOption, script, None, None) ⇒
      new Arguments(
        className = javaClassNameOption getOrElse { throw new IllegalArgumentException(s"language='$language' requires a class name") } )
  }

  def newModule(arguments: ModuleArguments) = new StandardJavaModule(arguments.asInstanceOf[Arguments])

  final case class Arguments(val className: String) extends JavaModule.Arguments {
    val moduleFactory = StandardJavaModule
  }
}
