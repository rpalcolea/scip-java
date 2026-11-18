package tests

import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

import scala.jdk.CollectionConverters._

import com.sourcegraph.Scip
import com.sourcegraph.scip_semanticdb._
import com.sourcegraph.semanticdb_javac.Semanticdb._
import munit.FunSuite

class SignatureFormattingSuite extends FunSuite with TempDirectories {
  private val directory = new DirectoryFixture()
  override def munitFixtures: Seq[Fixture[_]] =
    super.munitFixtures ++ List(directory)

  private def id(symbol: String): Tree = Tree
    .newBuilder()
    .setIdTree(IdTree.newBuilder().setSymbol(symbol))
    .build()

  private def string(value: String): Tree = Tree
    .newBuilder()
    .setLiteralTree(
      LiteralTree
        .newBuilder()
        .setConstant(
          Constant
            .newBuilder()
            .setStringConstant(StringConstant.newBuilder().setValue(value))
        )
    )
    .build()

  // Scala's AssignTree uses wire field 9, where this Java schema has AnnotationTree.
  // Round-trip the actual message bytes to retain the unknown lhs Type.3 field.
  private def scalaAssignment(name: String, value: Tree): Tree = {
    val assignment = AssignTree
      .newBuilder()
      .setLhs(id(name))
      .setRhs(value)
      .build()
    Tree
      .newBuilder()
      .setAnnotationTree(AnnotationTree.parseFrom(assignment.toByteArray))
      .build()
  }

  private def array(values: Tree*): Tree = Tree
    .newBuilder()
    .setApplyTree(ApplyTree.newBuilder().addAllArguments(values.asJava))
    .build()

  private def annotation(name: String, parameters: Tree*): AnnotationTree =
    AnnotationTree
      .newBuilder()
      .setTpe(
        Type.newBuilder().setTypeRef(TypeRef.newBuilder().setSymbol(name))
      )
      .addAllParameters(parameters.asJava)
      .build()

  private def clazz(
      name: String,
      language: Language,
      annotations: AnnotationTree*
  ): SymbolInformation = SymbolInformation
    .newBuilder()
    .setSymbol(s"example/$name#")
    .setDisplayName(name)
    .setKind(SymbolInformation.Kind.CLASS)
    .setLanguage(language)
    .setSignature(
      Signature
        .newBuilder()
        .setClassSignature(ClassSignature.getDefaultInstance)
    )
    .addAllAnnotations(annotations.asJava)
    .build()

  private def format(info: SymbolInformation): String =
    new SignatureFormatter(
      info,
      new Symtab(TextDocument.newBuilder().addSymbols(info).build())
    ).formatSymbol()

  test("Scala class-array annotation preserves assignment bytes") {
    val info = clazz(
      "AnnotationTest",
      Language.SCALA,
      annotation(
        "org/springframework/context/annotation/Import#",
        scalaAssignment("value", array(id("example/Config#")))
      )
    )
    assertNoDiff(
      format(info),
      "@Import(Array(classOf[Config]))\nclass AnnotationTest"
    )
  }

  test("Scala annotation retains multiple argument names and class literals") {
    val info = clazz(
      "Example",
      Language.SCALA,
      annotation(
        "example/Import#",
        scalaAssignment(
          "value",
          array(id("example/Config#"), id("example/Other#"))
        ),
        scalaAssignment("name", string("batch"))
      )
    )
    assertNoDiff(
      format(info),
      """@Import(value = Array(classOf[Config], classOf[Other]), name = "batch")
        |class Example""".stripMargin
    )
  }

  test("Java nested annotations retain their field-9 meaning") {
    val info = clazz(
      "Example",
      Language.JAVA,
      annotation(
        "example/Container#",
        Tree
          .newBuilder()
          .setAnnotationTree(annotation("example/Nested#", string("ok")))
          .build()
      )
    )
    assertNoDiff(
      format(info),
      """@Container(@Nested("ok"))
        |class Example""".stripMargin
    )
  }

  test("Java named value and array arguments retain Java syntax") {
    val values = Tree
      .newBuilder()
      .setApplyTree(
        ApplyTree
          .newBuilder()
          .setFunction(id("scala/Array#"))
          .addArguments(string("first"))
          .addArguments(string("second"))
      )
      .build()
    val argument = Tree
      .newBuilder()
      .setAssignTree(
        AssignTree
          .newBuilder()
          .setLhs(id("example/Import#value()."))
          .setRhs(values)
      )
      .build()
    val info = clazz(
      "Example",
      Language.JAVA,
      annotation("example/Import#", argument)
    )
    assertNoDiff(
      format(info),
      """@Import({"first", "second"})
        |class Example""".stripMargin
    )
  }

  test("empty Scala annotation array is retained") {
    val info = clazz(
      "Example",
      Language.SCALA,
      annotation("example/Import#", scalaAssignment("value", array()))
    )
    assertNoDiff(format(info), "@Import(Array())\nclass Example")
  }

  test("semanticdb-scalac 4.15.2 Java-annotation arguments are formatted") {
    val input = getClass.getResourceAsStream(
      "/scala-annotation/Annotated.scala.semanticdb"
    )
    assert(input != null)
    val document =
      try TextDocuments.parseFrom(input).getDocuments(0)
      finally input.close()
    def signature(name: String): String = format(
      document.getSymbolsList.asScala.find(_.getSymbol == s"example/$name#").get
    )
    assert(
      document
        .getSymbolsList
        .asScala
        .exists(_.getAnnotationsList.asScala.exists(_.getParametersCount > 0))
    )
    assertNoDiff(
      signature("Annotated"),
      "@Import(Array(classOf[Config]))\nclass Annotated"
    )
    assertNoDiff(
      signature("NamedAnnotated"),
      """@Import(value = Array(classOf[Config], classOf[OtherConfig]), name = "batch")
        |class NamedAnnotated""".stripMargin
    )
  }

  for (parallel <- List(false, true)) {
    test(
      s"signature failure preserves symbols, occurrences and other documents (parallel=$parallel)"
    ) {
      val root = directory()
      val bad = clazz(
        "Bad",
        Language.SCALA,
        annotation(
          "example/Unsupported#",
          Tree
            .newBuilder()
            .setOriginalTree(OriginalTree.getDefaultInstance)
            .build()
        )
      ).toBuilder
        .addOverriddenSymbols("external/Base#")
        .setDocumentation(
          Documentation
            .newBuilder()
            .setMessage("Keep this class documentation.")
        )
        .build()
      val good = clazz("Good", Language.SCALA)
      val other = clazz("Other", Language.SCALA)
      def write(name: String, symbols: SymbolInformation*): Unit = {
        val document = TextDocument
          .newBuilder()
          .setUri(s"src/$name.scala")
          .setLanguage(Language.SCALA)
          .addAllSymbols(symbols.asJava)
        for ((info, line) <- symbols.zipWithIndex) {
          document.addOccurrences(
            SymbolOccurrence
              .newBuilder()
              .setSymbol(info.getSymbol)
              .setRole(SymbolOccurrence.Role.DEFINITION)
              .setRange(
                Range
                  .newBuilder()
                  .setStartLine(line)
                  .setEndLine(line)
                  .setEndCharacter(3)
              )
          )
        }
        document.addOccurrences(
          SymbolOccurrence
            .newBuilder()
            .setSymbol("external/Library#run().")
            .setRole(SymbolOccurrence.Role.REFERENCE)
            .setRange(
              Range
                .newBuilder()
                .setStartLine(4)
                .setEndLine(4)
                .setEndCharacter(3)
            )
        )
        val path = root.resolve(s"META-INF/semanticdb/$name.scala.semanticdb")
        Files.createDirectories(path.getParent)
        Files.write(
          path,
          TextDocuments.newBuilder().addDocuments(document).build().toByteArray
        )
      }
      write("Example", bad, good)
      write("Other", other)
      val errors = new CopyOnWriteArrayList[Throwable]()
      val reporter =
        new ScipSemanticdbReporter {
          override def error(error: Throwable): Unit = {
            errors.add(error);
            ()
          }
        }
      val output = root.resolve("index.scip")
      ScipSemanticdb.run(
        new ScipSemanticdbOptions(
          Collections.singletonList(root),
          output,
          root,
          reporter,
          Scip.ToolInfo.newBuilder().setName("test").build(),
          ScipOutputFormat.TYPED_PROTOBUF,
          parallel,
          Collections.emptyList(),
          true,
          false,
          true
        )
      )
      assert(Files.size(output) > 0)
      val documents =
        Scip
          .Index
          .parseFrom(Files.readAllBytes(output))
          .getDocumentsList
          .asScala
      assertEquals(
        documents.map(_.getRelativePath).toSet,
        Set("src/Example.scala", "src/Other.scala")
      )
      val symbols =
        documents
          .flatMap(_.getSymbolsList.asScala)
          .map(s => s.getDisplayName -> s)
          .toMap
      assertEquals(symbols.keySet, Set("Bad", "Good", "Other"))
      assert(!symbols("Bad").hasSignatureDocumentation)
      assertEquals(
        symbols("Bad").getDocumentationList.asScala.toList,
        List("Keep this class documentation.")
      )
      assert(
        symbols("Bad")
          .getRelationshipsList
          .asScala
          .exists(_.getSymbol.endsWith("external/Base#"))
      )
      assertEquals(
        symbols("Good").getSignatureDocumentation.getText,
        "class Good"
      )
      assertEquals(
        symbols("Other").getSignatureDocumentation.getText,
        "class Other"
      )
      val occurrences = documents.flatMap(_.getOccurrencesList.asScala)
      assertEquals(
        occurrences.count(_.getSymbol.endsWith("external/Library#run().")),
        2
      )
      assert(occurrences.exists(_.getSymbol.endsWith("example/Bad#")))
      assertEquals(errors.size(), 1)
      assert(errors.get(0).isInstanceOf[SignatureFormatterException])
    }
  }
}
