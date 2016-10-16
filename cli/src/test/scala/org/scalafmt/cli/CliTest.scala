package org.scalafmt.cli

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import org.scalafmt.Error.MisformattedFile
import org.scalafmt.config.Config
import org.scalafmt.config.ScalafmtConfig
import org.scalafmt.util.DiffAssertions
import org.scalafmt.util.FileOps
import org.scalafmt.util.GitOps
import org.scalafmt.util.logger
import org.scalatest.FunSuite

class CliTest extends FunSuite with DiffAssertions {
  import FileTestOps._

  def getMockOptions(baseDir: File): CliOptions =
    getMockOptions(baseDir, baseDir)

  def getMockOptions(baseDir: File, workingDir: File): CliOptions = {
    CliOptions.default.copy(
      gitOpsConstructor = x => new FakeGitOps(baseDir),
      common = CliOptions.default.common.copy(
        workingDirectory = workingDir
      )
    )
  }

  val baseCliOptions: CliOptions = getMockOptions(
    File.createTempFile("base", "dir"))

  def getConfig(args: Array[String]): CliOptions = {
    Cli.getConfig(args, baseCliOptions).get
  }
  val unformatted = """
                      |object a    extends   App {
                      |pr("h")
                      |}
                    """.stripMargin
  // Using maxColumn 10 just to see the CLI uses the custom style.
  val expected10 = """|object a
                      |    extends App {
                      |  pr(
                      |    "h"
                      |  )
                      |}""".stripMargin
  val formatted = """|object a extends App {
                     |  pr("h")
                     |}""".stripMargin
  val customConfig =
    """
      |project.git = true
      |maxColumn   = 2
    """.stripMargin
  val sbtOriginal =
    """|lazy val x = project
       |   lazy val y    = project
       |   """.stripMargin

  val sbtExpected =
    """|lazy val x =
       |  project
       |lazy val y =
       |  project""".stripMargin

  def gimmeConfig(string: String): ScalafmtConfig =
    Config.fromHocon(string) match {
      case Right(e) => e
      case Left(e) => throw e
    }

  test("scalafmt -i --file tmpFile") {
    val originalTmpFile = Files.createTempFile("prefix", ".scala")
    Files.write(originalTmpFile, unformatted.getBytes)
    val args = Array(
      "--config",
      "\"{maxColumn=7,style=IntelliJ}\"",
      "--in-place",
      "--files",
      originalTmpFile.toFile.getPath
    )
    val formatInPlace = getConfig(args)
    Cli.run(formatInPlace)
    val obtained = FileOps.readFile(originalTmpFile.toString)
    assertNoDiff(obtained, expected10)
  }

  test("scalafmt --file tmpFile prints to stdout") {
    val originalTmpFile = Files.createTempFile("prefix", ".scala")
    Files.write(originalTmpFile, unformatted.getBytes)
    val args = Array(
      "-f",
      originalTmpFile.toFile.getPath
    )
    val baos = new ByteArrayOutputStream()
    val ps = new PrintStream(baos)
    val init = baseCliOptions.copy(
      common = baseCliOptions.common.copy(out = ps)
    )
    val auto = Cli.getConfig(args, init).get
    Cli.run(auto)
    val obtained = new String(baos.toByteArray, StandardCharsets.UTF_8)
    assertNoDiff(obtained, formatted)
  }

  test("scalafmt --stdin --assume-filename") {
    val args = Array(
      "--stdin",
      "--assume-filename",
      "build.sbt",
      "--config",
      "\"{maxColumn=7,style=IntelliJ}\""
    )
    val printToStdout = getConfig(args)
    val bais = new ByteArrayInputStream(sbtOriginal.getBytes)
    val baos = new ByteArrayOutputStream()
    val ps = new PrintStream(baos)
    Cli.run(
      printToStdout.copy(
        common = printToStdout.common.copy(
          out = ps,
          in = bais
        )
      ))
    val obtained = new String(baos.toByteArray, StandardCharsets.UTF_8)
    assertNoDiff(obtained, sbtExpected)
  }

  test("scalafmt --test --file tmpFile") {
    val tmpFile = Files.createTempFile("prefix", ".scala")
    Files.write(tmpFile, unformatted.getBytes)
    val args = Array(
      "--files",
      tmpFile.toFile.getPath,
      "--test"
    )
    val formatInPlace = getConfig(args)
    intercept[MisformattedFile] {
      Cli.run(formatInPlace)
    }
  }

  test("scalafmt -i -f foo.randomsuffix is formatted") {
    val tmpFile = Files.createTempFile("prefix", "randomsuffix")
    Files.write(tmpFile, unformatted.getBytes)
    val args = Array(
      "-i",
      "-f",
      tmpFile.toFile.getAbsolutePath
    )
    Cli.main(args)
    val obtained = FileOps.readFile(tmpFile.toString)
    assertNoDiff(obtained, formatted)
  }

  test("handles .scala and .sbt files") {
    val input = string2dir(
      s"""|/foobar.scala
          |object    A {  }
          |/foo.sbt
          |lazy   val x   = project
          |""".stripMargin
    )
    val expected =
      s"""|/foo.sbt
          |lazy val x = project
          |
          |/foobar.scala
          |object A {}
          |""".stripMargin
    val options = getConfig(
      Array(
        "--files",
        input.getAbsolutePath,
        "-i"
      )
    )
    Cli.run(options)
    val obtained = dir2string(input)
    assertNoDiff(obtained, expected)
  }

  test("excludefilters are respected") {
    val input = string2dir(
      s"""|/foo.sbt
          |lazy   val x   = project
          |/target/generated.scala
          |object    AAAAAAIgnoreME   {  }
          |""".stripMargin
    )
    val expected =
      s"""|/foo.sbt
          |lazy val x = project
          |
          |/target/generated.scala
          |object    AAAAAAIgnoreME   {  }
          |""".stripMargin
    val options = getConfig(
      Array(
        "--files",
        input.getAbsolutePath,
        "--exclude",
        "target",
        "-i"
      ))
    Cli.run(options)
    val obtained = dir2string(input)
    assertNoDiff(obtained, expected)
  }

  test("--file doesnotexists.scala throws error") {
    def check(filename: String): Unit = {
      val args = Array("-f", s"$filename.scala")
      intercept[FileNotFoundException] {
        Cli.main(args)
      }
    }
    check("notfound")
    check("target/notfound")
  }

  test("scalafmt (no arg) read config from git repo") {
    val input = string2dir(
      """|/foo.scala
         |object    FormatMe {
         |  val x = 1
         |}
         |/target/foo.scala
         |object A   { }
         |/.scalafmt.conf
         |maxColumn = 2
         |project.git = true
         |project.excludeFilters = [target]
         |""".stripMargin
    )

    val expected =
      """|/.scalafmt.conf
         |maxColumn = 2
         |project.git = true
         |project.excludeFilters = [target]
         |
         |/foo.scala
         |object FormatMe {
         |  val x =
         |    1
         |}
         |
         |/target/foo.scala
         |object A   { }
         |""".stripMargin

    val init: CliOptions = getMockOptions(input)
    val config = Cli.getConfig(Array.empty[String], init).get
    Cli.run(config)
    val obtained = dir2string(input)
    assertNoDiff(obtained, expected)
    val configTest = Cli.getConfig(Array("--test"), init).get
    Cli.run(configTest)
  }

  test("config is read even from nested dir") {
    val original = "object a { val x = 1 }"
    val expected =
      """|object a {
         |  val x =
         |    1
         |}""".stripMargin
    val input = string2dir(
      s"""|/nested/foo.scala
          |$original
          |/.scalafmt.conf
          |maxColumn = 2
          |project.git = true
          |""".stripMargin
    )
    val workingDir = new File(input, "nested")
    logger.elem(workingDir, FileOps.listFiles(workingDir))
    val options: CliOptions = {
      val mock = getMockOptions(input, workingDir)
      mock.copy(common = mock.common.copy(workingDirectory = workingDir))
    }
    val config = Cli.getConfig(Array("-i", "-f", "foo.scala"), options).get
    Cli.run(config)
    val obtained = FileOps.readFile(new File(workingDir, "foo.scala"))
    assertNoDiff(obtained, expected)
  }
}
