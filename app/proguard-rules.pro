# The accessibility service is referenced from the manifest and instantiated
# by the system; keep it (and its empty constructor) from being stripped.
-keep class com.ashorts.blocker.service.BlockerAccessibilityService { *; }
