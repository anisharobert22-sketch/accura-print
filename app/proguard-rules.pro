# The app uses no reflection, no serialisation library and no dynamic class
# loading, so R8's defaults are almost enough on their own. These keeps are
# belt-and-braces for the pieces the framework instantiates by name from the
# manifest — if R8 ever renamed one, the failure would be an app that installs
# and then does nothing.
-keep class ai.soulvai.accuraprint.MainActivity { *; }
-keep class ai.soulvai.accuraprint.PrintBridgeService { *; }
-keep class ai.soulvai.accuraprint.BootReceiver { *; }

# Line numbers make a crash report from a shop tablet worth reading.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# DantSu ESC/POS library: keep whole, it is small and R8 stripping a parser
# class it reaches via a static table would only show up as a print that
# silently does nothing on a real till.
-keep class com.dantsu.escposprinter.** { *; }
