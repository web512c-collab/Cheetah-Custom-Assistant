# Keep Assistant system services and accessibility entry points intact under R8
-keep class com.example.assistant.** { *; }
-keep class com.example.controller.CheetahAccessibilityService { *; }
-keep class com.example.voice.WakeWordDetectionService { *; }
-keep class com.example.voice.CheetahWakeWordService { *; }
-keep class com.example.voice.BundledYaSheetaAcousticModel { *; }
-keep class com.example.voice.BootCompletedReceiver { *; }
