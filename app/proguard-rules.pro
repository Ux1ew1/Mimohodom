# Called from local_llm.cpp through JNI.
-keepclassmembers class app.fastnotes.LocalLlm {
    private static void reportStage(int);
}
