# The document engine uses standard DOM interfaces, not reflective model serialization.
-keep class org.smallthingz.alldoc.IPdfRenderer$Stub { *; }
-keep class org.smallthingz.alldoc.IPdfRenderer$Stub$Proxy { *; }

# POI is used only by LegacyOfficeExtractor's bounded text paths. No UI, drawing,
# generic-record JSON diagnostics, or desktop number formatting is invoked.
# JVM tests deny all desktop graphics packages; unexpected linkage fails closed.
# These exact desktop classes are referenced only by retained POI diagnostic
# toString helpers and unused Escher geometry getters, not the preview adapters.
-dontwarn java.awt.Color
-dontwarn java.awt.Dimension
-dontwarn java.awt.Rectangle
-dontwarn java.awt.color.ColorSpace
-dontwarn java.awt.geom.AffineTransform
-dontwarn java.awt.geom.Dimension2D
-dontwarn java.awt.geom.Path2D
-dontwarn java.awt.geom.PathIterator
-dontwarn java.awt.geom.Point2D
-dontwarn java.awt.geom.Rectangle2D
-dontwarn java.awt.image.BufferedImage
-dontwarn java.awt.image.ColorModel
-dontwarn java.awt.image.ComponentColorModel
-dontwarn java.awt.image.DirectColorModel
-dontwarn java.awt.image.IndexColorModel
-dontwarn java.awt.image.PackedColorModel
# Optional build annotations and OSGi service discovery in Log4j API.
-dontwarn aQute.bnd.annotation.**
-dontwarn edu.umd.cs.findbugs.annotations.**
-dontwarn org.jspecify.annotations.**
-dontwarn org.osgi.**
