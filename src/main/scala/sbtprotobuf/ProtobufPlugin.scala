package sbtprotobuf

import sbt._
import Keys._

import java.io.File


object ProtobufPlugin extends Plugin {
  val protobufConfig = config("protobuf")

  val includePaths = TaskKey[Seq[File]]("protobuf-include-paths", "The paths that contain *.proto dependencies.")
  val protoc = SettingKey[String]("protobuf-protoc", "The path+name of the protoc executable.")
  val runProtoc = TaskKey[Seq[String] => Int]("protobuf-run-protoc", "A function that executes the protobuf compiler with the given arguments, returning the exit code of the compilation run.")
  val externalIncludePath = SettingKey[File]("protobuf-external-include-path", "The path to which protobuf:libraryDependencies are extracted and which is used as protobuf:includePath for protoc")
  val generatedTargets = SettingKey[Seq[(File,String)]]("protobuf-generated-targets", "Targets for protoc: target directory and glob for generated source files")
  val generate = TaskKey[Seq[File]]("protobuf-generate", "Compile the protobuf sources.")
  val unpackDependencies = TaskKey[UnpackedDependencies]("protobuf-unpack-dependencies", "Unpack dependencies.")
  val protocOptions = SettingKey[Seq[String]]("protobuf-protoc-options", "Additional options to be passed to protoc")

  lazy val protobufSettings: Seq[Setting[_]] = inConfig(protobufConfig)(Seq[Setting[_]](
    sourceDirectory := { (sourceDirectory in Compile).value / "protobuf" },
    sourceDirectories := (sourceDirectory.value :: Nil),
    includeFilter := "*.proto",
    javaSource := {(sourceManaged in Compile).value / "compiled_protobuf" },
    externalIncludePath := (target.value / "protobuf_external"),
    protoc := "protoc",
    runProtoc := (args => Process(protoc.value, args) ! streams.value.log),
    version := "3.0.0",

    generatedTargets := Nil,
    generatedTargets += Tuple2((javaSource in protobufConfig).value, "*.java"), // add javaSource to the list of patterns

    protocOptions := Nil,
    protocOptions ++= { // if a java target is provided, add java generation option
      (generatedTargets in protobufConfig).value.find(_._2.endsWith(".java")) match {
        case Some(targetForJava) => Seq("--java_out=%s".format(targetForJava._1.getCanonicalPath))
        case None => Nil
      }
    },

    managedClasspath := {
      Classpaths.managedJars(protobufConfig, classpathTypes.value, update.value)
    },

    unpackDependencies := unpackDependenciesTask.value,

    includePaths := ((sourceDirectory in protobufConfig).value :: Nil),
    includePaths += externalIncludePath.value,

    generate := sourceGeneratorTask.dependsOn(unpackDependencies).value

  )) ++ Seq[Setting[_]](
    watchSources ++= ((sourceDirectory in protobufConfig).value ** "*.proto").get,
    sourceGenerators in Compile += (generate in protobufConfig).taskValue,
    cleanFiles ++= (generatedTargets in protobufConfig).value.map{_._1},
    cleanFiles += (externalIncludePath in protobufConfig).value,
    managedSourceDirectories in Compile ++= (generatedTargets in protobufConfig).value.map{_._1},
    libraryDependencies += ("com.google.protobuf" % "protobuf-java" % (version in protobufConfig).value),
    ivyConfigurations += protobufConfig
  )

  case class UnpackedDependencies(dir: File, files: Seq[File])

  private[this] def executeProtoc(protocCommand: Seq[String] => Int, schemas: Set[File], includePaths: Seq[File], protocOptions: Seq[String], log: Logger) : Int =
    try {
      val incPath = includePaths.map("-I" + _.getCanonicalPath)
      protocCommand(incPath ++ protocOptions ++ schemas.map(_.getCanonicalPath))
    } catch { case e: Exception =>
      throw new RuntimeException("error occurred while compiling protobuf files: %s" format(e.getMessage), e)
    }

  private[this] def compile(protocCommand: Seq[String] => Int, schemas: Set[File], includePaths: Seq[File], protocOptions: Seq[String], generatedTargets: Seq[(File, String)], log: Logger) = {
    val generatedTargetDirs = generatedTargets.map(_._1)
    generatedTargetDirs.foreach{ targetDir =>
      IO.delete(targetDir)
      targetDir.mkdirs()
    }

    if(!schemas.isEmpty){
      log.info("Compiling %d protobuf files to %s".format(schemas.size, generatedTargetDirs.mkString(",")))
      log.debug("protoc options:")
      protocOptions.map("\t"+_).foreach(log.debug(_))
      schemas.foreach(schema => log.info("Compiling schema %s" format schema))

      val exitCode = executeProtoc(protocCommand, schemas, includePaths, protocOptions, log)
      if (exitCode != 0)
        sys.error("protoc returned exit code: %d" format exitCode)

      log.info("Compiling protobuf")
      generatedTargetDirs.foreach{ dir =>
        log.info("Protoc target directory: %s".format(dir.absolutePath))
      }

      (generatedTargets.flatMap{ot => (ot._1 ** ot._2).get}).toSet
    } else {
      Set[File]()
    }
  }

  private[this] def unpack(deps: Seq[File], extractTarget: File, log: Logger): Seq[File] = {
    IO.createDirectory(extractTarget)
    deps.flatMap { dep =>
      val seq = IO.unzip(dep, extractTarget, "*.proto").toSeq
      if (!seq.isEmpty) log.debug("Extracted " + seq.mkString("\n * ", "\n * ", ""))
      seq
    }
  }

  private[this] def sourceGeneratorTask =
    (streams, sourceDirectories in protobufConfig, includePaths in protobufConfig, includeFilter in protobufConfig, excludeFilter in protobufConfig, protocOptions in protobufConfig, generatedTargets in protobufConfig, streams, runProtoc) map {
    (out, srcDirs, includePaths, includeFilter, excludeFilter, protocOpts, otherTargets, streams, protocCommand) =>
      val schemas = srcDirs.toSet[File].flatMap(srcDir => (srcDir ** (includeFilter -- excludeFilter)).get.map(_.getAbsoluteFile))
      val cachedCompile = FileFunction.cached(streams.cacheDirectory / "protobuf", inStyle = FilesInfo.lastModified, outStyle = FilesInfo.exists) { (in: Set[File]) =>
        compile(protocCommand, schemas, includePaths, protocOpts, otherTargets, out.log)
      }
      cachedCompile(schemas).toSeq
  }

  private[this] def unpackDependenciesTask = (streams, managedClasspath in protobufConfig, externalIncludePath in protobufConfig) map {
    (out, deps, extractTarget) =>
      val extractedFiles = unpack(deps.map(_.data), extractTarget, out.log)
      UnpackedDependencies(extractTarget, extractedFiles)
  }
}
