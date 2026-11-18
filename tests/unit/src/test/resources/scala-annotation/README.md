# Scala Java-annotation fixture

`Annotated.scala.semanticdb` was generated from the adjacent sources with Scala
2.13.16 and `semanticdb-scalac` 4.15.2. It covers the Scala encoding of
`@Import(Array(classOf[Config]))`, including the default `value`
argument and multiple explicitly named arguments. Tests read the binary directly;
they do not download or invoke a Scala compiler.

Scala encodes `Tree.AssignTree` at protobuf field 9. The Java schema uses that
field for its custom `AnnotationTree`, so decoding must preserve unknown fields
before reinterpreting the assignment. The compiler also emits annotation arrays
as apply trees without a function. A text-format reconstruction that discards
unknown fields does not reproduce this input faithfully.

To regenerate from the repository root, supply the Scala 2.13.16 compiler,
library, and reflect JARs in `SCALA_COMPILER_CLASSPATH`, the library JAR path in
`SCALA_LIBRARY_JAR`, and the `semanticdb-scalac_2.13.16:4.15.2` JAR path in
`SEMANTICDB_PLUGIN`:

```sh
mkdir -p target/scala-annotation-fixture
javac -proc:none -d target/scala-annotation-fixture \
  tests/unit/src/test/resources/scala-annotation/Import.java
java -cp "$SCALA_COMPILER_CLASSPATH" scala.tools.nsc.Main \
  -classpath "target/scala-annotation-fixture:$SCALA_LIBRARY_JAR" \
  -d target/scala-annotation-fixture \
  -Xplugin:"$SEMANTICDB_PLUGIN" -Yrangepos \
  -P:semanticdb:sourceroot:tests/unit/src/test/resources/scala-annotation \
  -P:semanticdb:targetroot:target/scala-annotation-fixture \
  tests/unit/src/test/resources/scala-annotation/Annotated.scala
cp target/scala-annotation-fixture/META-INF/semanticdb/Annotated.scala.semanticdb \
  tests/unit/src/test/resources/scala-annotation/Annotated.scala.semanticdb
```

Run `./sbt 'unit/testOnly tests.SignatureFormattingSuite'` to validate the fixture
and conversion regressions.
