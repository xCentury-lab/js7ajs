package js7.controller.tests

import java.io.IOException
import java.nio.file.Files.{createDirectories, createDirectory, deleteIfExists, exists}
import java.nio.file.Path
import js7.base.io.file.FileUtils.deleteDirectoryContentRecursively
import js7.base.io.file.FileUtils.syntax._
import js7.base.log.Logger
import js7.controller.tests.TestEnvironment._
import js7.data.agent.AgentPath
import js7.data.item.{ItemPath, SourceType}

/**
  * @author Joacim Zschimmer
  */
final class TestEnvironment(agentPaths: Seq[AgentPath], temporaryDirectory: Path)
extends AutoCloseable {

  if (exists(temporaryDirectory)) {
    logger.warn(s"Deleting $temporaryDirectory")
    deleteDirectoryContentRecursively(temporaryDirectory)
  }

  createDirectories(controllerDir / "config/private")
  createDirectories(controllerDir / "data")
  for (agentPath <- agentPaths) {
    createDirectories(agentDir(agentPath) / "config/private")
    createDirectories(agentDir(agentPath) / "config/executables")
    createDirectory(agentDir(agentPath) / "data")
  }

  def close(): Unit = {
    deleteDirectoryContentRecursively(temporaryDirectory)
    try deleteIfExists(temporaryDirectory)
    catch { case t: IOException => logger.debug(s"Delete $temporaryDirectory: $t", t)}
  }

   def controllerDir: Path =
    temporaryDirectory / "controller"

  def agentFile(agentPath: AgentPath, path: ItemPath, t: SourceType): Path =
    agentDir(agentPath) / "config/live" resolve path.toFile(t)

  def agentDir(name: AgentPath): Path =
    agentsDir / name.string

  def agentsDir = temporaryDirectory / "agents"
}

object TestEnvironment {
  private val logger = Logger(getClass)
}
