# sherpa-onnx: the JNI library reads config fields and constructs result objects by name
-keep class com.k2fsa.sherpa.onnx.** { *; }

# keep line numbers in crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
