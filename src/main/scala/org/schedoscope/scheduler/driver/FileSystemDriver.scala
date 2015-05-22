package org.schedoscope.scheduler.driver

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.security.PrivilegedAction
import scala.Array.canBuildFrom
import scala.collection.mutable.HashMap
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration
import scala.concurrent.future
import org.apache.commons.io.FileUtils
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.FileStatus
import org.apache.hadoop.fs.FileSystem
import org.apache.hadoop.fs.FileUtil
import org.apache.hadoop.fs.Path
import org.apache.hadoop.security.UserGroupInformation
import org.joda.time.LocalDateTime
import org.schedoscope.DriverSettings
import org.schedoscope.Settings
import org.schedoscope.dsl.transformations.Copy
import org.schedoscope.dsl.transformations.CopyFrom
import org.schedoscope.dsl.transformations.Delete
import org.schedoscope.dsl.transformations.FilesystemTransformation
import org.schedoscope.dsl.transformations.IfExists
import org.schedoscope.dsl.transformations.IfNotExists
import org.schedoscope.dsl.transformations.Move
import org.schedoscope.dsl.transformations.Touch
import org.schedoscope.dsl.transformations.StoreFrom
import org.schedoscope.dsl.transformations.MkDir
import org.schedoscope.scheduler.driver.FileSystemDriver._

class FileSystemDriver(val ugi: UserGroupInformation, val conf: Configuration) extends Driver[FilesystemTransformation] {

  override def transformationName = "filesystem"

  implicit val executionContext = Settings().system.dispatchers.lookup("akka.actor.future-driver-dispatcher")

  def run(t: FilesystemTransformation): DriverRunHandle[FilesystemTransformation] =
    new DriverRunHandle(this, new LocalDateTime(), t, future {
      doRun(t)
    })

  def doRun(t: FilesystemTransformation): DriverRunState[FilesystemTransformation] =
    t match {

      case IfExists(path, op) => doAs(() => {
        if (fileSystem(path, conf).exists(new Path(path)))
          doRun(op)
        else
          DriverRunSucceeded(this, s"Path ${path} does not yet exist.")
      })

      case IfNotExists(path, op) => doAs(() => {
        if (!fileSystem(path, conf).exists(new Path(path)))
          doRun(op)
        else
          DriverRunSucceeded(this, s"Path ${path} already exists.")
      })

      case CopyFrom(from, view, recursive) => doAs(() => copy(from, view.fullPath, recursive))
      case StoreFrom(inputStream, view) => doAs(() => storeFromStream(inputStream, view.fullPath))
      case Copy(from, to, recursive) => doAs(() => copy(from, to, recursive))
      case Move(from, to) => doAs(() => move(from, to))
      case Delete(path, recursive) => doAs(() => delete(path, recursive))
      case MkDir(path) => doAs(() => mkdirs(path))
      case Touch(path) => doAs(() => touch(path))

      case _ => throw DriverException("FileSystemDriver can only run file transformations.")
    }

  def doAs(f: () => DriverRunState[FilesystemTransformation]): DriverRunState[FilesystemTransformation] = ugi.doAs(new PrivilegedAction[DriverRunState[FilesystemTransformation]]() {
    def run(): DriverRunState[FilesystemTransformation] = {
      f()
    }
  })

  def storeFromStream(inputStream: InputStream, to: String): DriverRunState[FilesystemTransformation] = {
    def inputStreamToFile(inputStream: InputStream) = {
      val remainingPath = "stream.out"
      val tempDir = Files.createTempDirectory("classpath").toFile.toString()
      val tempFile = new File(tempDir + File.separator + remainingPath)
      FileUtils.touch(tempFile)
      FileUtils.copyInputStreamToFile(inputStream, tempFile)

      tempFile.toURI().toString()
    }

    try {
      val streamInFile = inputStreamToFile(inputStream)

      val fromFS = fileSystem(streamInFile, conf)
      val toFS = fileSystem(to, conf)

      toFS.mkdirs(new Path(to))

      FileUtil.copy(fromFS, new Path(streamInFile), toFS, new Path(to), false, true, conf)

      DriverRunSucceeded(this, s"Storing from InputStream to ${to} succeeded")
    } catch {
      case i: IOException => DriverRunFailed(this, s"Caught IO exception while storing InputStream to ${to}", i)
      case t: Throwable => throw DriverException(s"Runtime exception caught while copying InputStream to ${to}", t)
    }
  }

  def copy(from: String, to: String, recursive: Boolean): DriverRunState[FilesystemTransformation] = {
    def classpathResourceToFile(classpathResourceUrl: String) = {
      val remainingPath = classpathResourceUrl.replace("classpath://", "")
      val tempDir = Files.createTempDirectory("classpath").toFile.toString()
      val tempFile = new File(tempDir + File.separator + remainingPath)
      FileUtils.touch(tempFile)
      FileUtils.copyInputStreamToFile(this.getClass().getResourceAsStream("/" + remainingPath), tempFile)

      tempFile.toURI().toString()
    }

    def inner(fromFS: FileSystem, toFS: FileSystem, files: Seq[FileStatus], to: Path): Unit = {
      toFS.mkdirs(to)
      if (recursive) {
        files.filter(p => (p.isDirectory() && !p.getPath().getName().startsWith("."))).
          foreach(path => {
            inner(fromFS, toFS, fromFS.globStatus(new Path(path.getPath(), "*")), new Path(to, path.getPath().getName()))
          })
      }
      files.filter(p => !p.isDirectory()).map(status => status.getPath()).foreach { p =>
        FileUtil.copy(fromFS, p, toFS, to, false, true, conf)
      }
    }

    try {
      val fromIncludingResources = if (from.startsWith("classpath://"))
        classpathResourceToFile(from)
      else
        from

      val fromFS = fileSystem(fromIncludingResources, conf)
      val toFS = fileSystem(to, conf)
      inner(fromFS, toFS, listFiles(fromIncludingResources), new Path(to))

      DriverRunSucceeded(this, s"Copy from ${from} to ${to} succeeded")
    } catch {
      case i: IOException => DriverRunFailed(this, s"Caught IO exception while copying ${from} to ${to}", i)
      case t: Throwable => throw DriverException(s"Runtime exception caught while copying ${from} to ${to}", t)
    }
  }

  def delete(from: String, recursive: Boolean): DriverRunState[FilesystemTransformation] =
    try {
      val fromFS = fileSystem(from, conf)
      val files = listFiles(from)
      files.foreach(status => fromFS.delete(status.getPath(), recursive))

      DriverRunSucceeded(this, s"Deletion of ${from} succeeded")
    } catch {
      case i: IOException => DriverRunFailed(this, s"Caught IO exception while deleting ${from}", i)
      case t: Throwable => throw DriverException(s"Runtime exception while deleting ${from}", t)
    }

  def touch(path: String): DriverRunState[FilesystemTransformation] =
    try {
      val filesys = fileSystem(path, conf)

      val toCreate = new Path(path)

      filesys.create(new Path(path))

      DriverRunSucceeded(this, s"Touching of ${path} succeeded")
    } catch {
      case i: IOException => DriverRunFailed(this, s"Caught IO exception while touching ${path}", i)
      case t: Throwable => throw DriverException(s"Runtime exception while touching ${path}", t)
    }

  def mkdirs(path: String): DriverRunState[FilesystemTransformation] =
    try {
      val filesys = fileSystem(path, conf)

      filesys.mkdirs(new Path(path))

      DriverRunSucceeded(this, s"Touching of ${path} succeeded")
    } catch {
      case i: IOException => DriverRunFailed(this, s"Caught IO exception while making dirs ${path}", i)
      case t: Throwable => throw DriverException(s"Runtime exception while making dirs ${path}", t)
    }

  def move(from: String, to: String): DriverRunState[FilesystemTransformation] =
    try {
      val fromFS = fileSystem(from, conf)
      val toFS = fileSystem(to, conf)
      val files = listFiles(from)

      FileUtil.copy(fromFS, FileUtil.stat2Paths(files), toFS, new Path(to), true, true, conf)

      DriverRunSucceeded(this, s"Moving from ${from} to ${to} succeeded")
    } catch {
      case i: IOException => DriverRunFailed(this, s"Caught IO exception while  moving from ${from} to ${to}", i)
      case t: Throwable => throw DriverException(s"Runtime exception while moving from ${from} to ${to}", t)
    }

  def fileChecksums(paths: List[String], recursive: Boolean): List[String] = {
    paths.flatMap(p => {
      val fs = fileSystem(p, conf)
      val path = new Path(p)
      if (fs.isFile(path))
        List(FileSystemDriver.fileChecksum(fs, path, p))
      else if (recursive)
        fileChecksums(listFiles(p + "/*").map(f => f.getPath.toString()).toList, recursive)
      else
        List()
    }).sorted
  }

  def listFiles(path: String): Array[FileStatus] = {
    val files = fileSystem(path, conf).globStatus(new Path(path))
    if (files != null)
      files
    else Array()
  }

  def localFilesystem: FileSystem = FileSystem.getLocal(conf)

  def filesystem = FileSystem.get(conf)

  override def deployAll(driverSettings: DriverSettings) = true
}

object FileSystemDriver {
  private def uri(pathOrUri: String) =
    try {
      new URI(pathOrUri)
    } catch {
      case _: Throwable => new File(pathOrUri).toURI()
    }

  def fileSystem(path: String, conf: Configuration) = FileSystem.get(uri(path), conf)

  def apply(ds: DriverSettings) = {
    new FileSystemDriver(Settings().userGroupInformation, Settings().hadoopConf)
  }

  private val checksumCache = new HashMap[String, String]()

  private def calcChecksum(fs: FileSystem, path: Path) =
    if (path == null)
      "null-checksum"
    else if (path.toString.endsWith(".jar"))
      path.toString
    else try {
      val cs = fs.getFileChecksum(path).toString()
      if (cs == null)
        path.toString()
      else
        cs
    } catch {
      case _: Throwable => path.toString()
    }

  def fileChecksum(fs: FileSystem, path: Path, pathString: String) = synchronized {
    checksumCache.getOrElseUpdate(pathString, calcChecksum(fs, path))
  }
}