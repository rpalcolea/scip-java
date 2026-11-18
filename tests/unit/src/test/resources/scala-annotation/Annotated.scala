package example

class Config
class OtherConfig

@Import(Array(classOf[Config]))
class Annotated

@Import(value = Array(classOf[Config], classOf[OtherConfig]), name = "batch")
class NamedAnnotated
