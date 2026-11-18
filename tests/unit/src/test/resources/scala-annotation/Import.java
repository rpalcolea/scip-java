package example;

public @interface Import {
  Class<?>[] value();
  String name() default "";
}
