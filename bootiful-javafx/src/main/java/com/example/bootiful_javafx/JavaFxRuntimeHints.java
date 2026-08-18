package com.example.bootiful_javafx;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

import java.util.List;
import java.util.stream.Stream;

///
/// This is the `META-INF/native-image/org.openjfx/javafx/reachability-metadata.json` the
/// native-image agent recorded (`mvn -Pagent spring-boot:run`), rewritten as a
/// [RuntimeHintsRegistrar] so it compiles, so it can carry an explanation of *why* each family is
/// in the list, and so it arrives by the same route as every other hint Spring's AOT processing
/// contributes.
///
/// The agent records reflection down to the individual method. This does not. A class that
/// JavaFX only ever finds by name is a class whose members we cannot predict, so every type here
/// is registered for every [MemberCategory] - and asking for `values()` rather than naming them
/// keeps that true when a future Spring adds one.
class JavaFxRuntimeHints implements RuntimeHintsRegistrar {

    /// Everything, asked for as `values()` rather than spelled out, so it stays everything the day
    /// Spring adds a category. Minus the deprecated ones: each is either a synonym for a surviving
    /// category, or - `PUBLIC_CLASSES`, `DECLARED_CLASSES` - an attribute GraalVM's metadata schema
    /// has since dropped and now greets with a build warning.
    private static final MemberCategory[] EVERYTHING = Stream.of(MemberCategory.values())
            .filter(category -> !isDeprecated(category))
            .toArray(MemberCategory[]::new);

    /// Glass is the sliver of JavaFX that sits on the platform's own windowing toolkit, and the
    /// traffic runs both ways: Cocoa delivers an event, and the native side reaches back into
    /// Java through JNI, looking up the class, the field or the method by name. Those lookups are
    /// invisible to native-image's static analysis, so every type the native side names is
    /// registered here for JNI as well as for reflection - including the handful of JDK types
    /// (`java.lang.Boolean`, `java.util.HashMap`) Glass constructs on the way back up.
    ///
    /// Much of this is platform-specific - `com.sun.glass.ui.mac` only exists in the macOS
    /// classifier of javafx-graphics - which is why registration goes through
    /// `registerTypeIfPresent`: on another platform those names simply are not there.
    private static final List<String> NATIVE_CALLBACKS = types(
            in("com.sun.glass.events",
                    "DndEvent", "GestureEvent", "KeyEvent", "MouseEvent", "SwipeGesture", "TouchEvent",
                    "ViewEvent", "WheelEvent", "WindowEvent"),
            in("com.sun.glass.ui",
                    "Accessible", "Accessible$EventHandler", "Accessible$ExecuteAction",
                    "Accessible$GetAttribute", "Application", "Application$EventHandler", "Clipboard",
                    "ClipboardAssistance", "CommonDialogs", "CommonDialogs$ExtensionFilter",
                    "CommonDialogs$FileChooserResult", "CommonDialogs$Type", "Cursor", "DelayedCallback",
                    "EventLoop", "EventLoop$State", "GestureSupport", "GestureSupport$GestureState",
                    "GestureSupport$GestureState$StateId", "GlassRobot", "GlassRobot$1", "HeaderButtonMetrics",
                    "HeaderButtonOverlay", "HeaderButtonOverlay$1", "HeaderButtonOverlay$2",
                    "HeaderButtonOverlay$3", "HeaderButtonOverlay$4", "HeaderButtonOverlay$5",
                    "HeaderButtonOverlay$6", "HeaderButtonOverlay$7", "HeaderButtonOverlay$8",
                    "HeaderButtonOverlay$9", "HeaderButtonOverlay$ButtonLayoutInfo",
                    "HeaderButtonOverlay$ButtonPlacement", "HeaderButtonOverlay$ButtonRegion",
                    "HeaderButtonOverlay$ButtonRegion$1", "HeaderButtonOverlay$ButtonRegion$2",
                    "HeaderButtonOverlay$ButtonSizeInfo", "HeaderButtonOverlay$ButtonVerticalAlignment",
                    "HeaderButtonOverlayHelper", "HeaderButtonOverlayHelper$Accessor", "InvokeLaterDispatcher",
                    "InvokeLaterDispatcher$Future", "InvokeLaterDispatcher$InvokeLaterSubmitter", "Menu",
                    "Menu$EventHandler", "MenuBar", "MenuItem", "MenuItem$Callback", "Pixels", "Pixels$Format",
                    "Platform", "PlatformFactory", "Screen", "Screen$EventHandler", "Size", "SystemClipboard",
                    "Timer", "TouchInputSupport", "TouchInputSupport$TouchCoord",
                    "TouchInputSupport$TouchCountListener", "View", "View$1", "View$2", "View$Capability",
                    "View$EventHandler", "Window", "Window$EventHandler", "Window$Level", "Window$State",
                    "Window$TrackingRectangle"),
            in("com.sun.glass.ui.delegate",
                    "ClipboardDelegate", "MenuBarDelegate", "MenuDelegate", "MenuItemDelegate"),
            in("com.sun.glass.ui.headless",
                    "HeadlessApplication", "HeadlessCursor", "HeadlessPixels", "HeadlessPlatformFactory",
                    "HeadlessPlatformFactory$HeadlessDnDClipboard",
                    "HeadlessPlatformFactory$HeadlessSystemClipboard", "HeadlessRobot", "HeadlessRobot$1",
                    "HeadlessRobot$MouseState", "HeadlessRobot$SpecialKeys", "HeadlessTimer", "HeadlessView",
                    "HeadlessWindow", "HeadlessWindowManager", "NestedRunnableProcessor",
                    "NestedRunnableProcessor$RunLoopEntry"),
            in("com.sun.glass.ui.mac",
                    "MacAccessible", "MacAccessible$1", "MacAccessible$MacAction", "MacAccessible$MacAttribute",
                    "MacAccessible$MacNotification", "MacAccessible$MacOrientation", "MacAccessible$MacRole",
                    "MacAccessible$MacSubrole", "MacAccessible$MacText", "MacApplication", "MacApplication$1",
                    "MacApplication$2", "MacApplication$3", "MacApplication$4", "MacClipboardDelegate",
                    "MacCommonDialogs", "MacCursor", "MacDnDClipboard", "MacFileNSURL", "MacGestureSupport",
                    "MacMenuBarDelegate", "MacMenuDelegate", "MacPasteboard", "MacPixels", "MacPlatformFactory",
                    "MacRobot", "MacSystemClipboard", "MacSystemClipboard$FormatEncoder", "MacTimer",
                    "MacTouchInputSupport", "MacTouchInputSupport$TouchPoint", "MacVariant", "MacView",
                    "MacWindow", "MacWindow$NSWindowToolbarStyle"),
            in("com.sun.glass.utils",
                    "NativeLibLoader"),
            in("com.sun.javafx.font.coretext",
                    "CGAffineTransform", "CGPoint", "CGRect", "CGSize"),
            in("java.lang",
                    "Boolean", "Class", "Integer", "Long", "Object", "Runnable", "String"),
            in("java.util",
                    "Collections", "HashMap", "List", "Map"),
            in("javafx.scene.paint",
                    "Color"),
            in("javafx.scene.shape",
                    "LineTo", "MoveTo"),
            in("sun.management",
                    "VMManagementImpl")
    );

    /// Prism builds a shader's class name out of the paint, the blend mode and the pipeline, then
    /// asks for it by that name. No amount of static analysis follows a string concatenation into
    /// a class, and no single run of the app touches more than a few, so the whole family is
    /// registered wholesale rather than one recording's worth.
    private static final List<String> PRISM_SHADERS =
            in("com.sun.prism.shader",
                    "AlphaOne_Color_AlphaTest_Loader", "AlphaOne_Color_Loader",
                    "AlphaOne_ImagePattern_AlphaTest_Loader", "AlphaOne_ImagePattern_Loader",
                    "AlphaOne_LinearGradient_AlphaTest_Loader", "AlphaOne_LinearGradient_Loader",
                    "AlphaOne_RadialGradient_AlphaTest_Loader", "AlphaOne_RadialGradient_Loader",
                    "AlphaTextureDifference_Color_AlphaTest_Loader", "AlphaTextureDifference_Color_Loader",
                    "AlphaTextureDifference_ImagePattern_AlphaTest_Loader",
                    "AlphaTextureDifference_ImagePattern_Loader",
                    "AlphaTextureDifference_LinearGradient_AlphaTest_Loader",
                    "AlphaTextureDifference_LinearGradient_Loader",
                    "AlphaTextureDifference_RadialGradient_AlphaTest_Loader",
                    "AlphaTextureDifference_RadialGradient_Loader", "AlphaTexture_Color_AlphaTest_Loader",
                    "AlphaTexture_Color_Loader", "AlphaTexture_ImagePattern_AlphaTest_Loader",
                    "AlphaTexture_ImagePattern_Loader", "AlphaTexture_LinearGradient_AlphaTest_Loader",
                    "AlphaTexture_LinearGradient_Loader", "AlphaTexture_RadialGradient_AlphaTest_Loader",
                    "AlphaTexture_RadialGradient_Loader", "DrawCircle_Color_AlphaTest_Loader",
                    "DrawCircle_Color_Loader", "DrawCircle_ImagePattern_AlphaTest_Loader",
                    "DrawCircle_ImagePattern_Loader", "DrawCircle_LinearGradient_PAD_AlphaTest_Loader",
                    "DrawCircle_LinearGradient_PAD_Loader", "DrawCircle_LinearGradient_REFLECT_AlphaTest_Loader",
                    "DrawCircle_LinearGradient_REFLECT_Loader", "DrawCircle_LinearGradient_REPEAT_AlphaTest_Loader",
                    "DrawCircle_LinearGradient_REPEAT_Loader", "DrawCircle_RadialGradient_PAD_AlphaTest_Loader",
                    "DrawCircle_RadialGradient_PAD_Loader", "DrawCircle_RadialGradient_REFLECT_AlphaTest_Loader",
                    "DrawCircle_RadialGradient_REFLECT_Loader", "DrawCircle_RadialGradient_REPEAT_AlphaTest_Loader",
                    "DrawCircle_RadialGradient_REPEAT_Loader", "DrawEllipse_Color_AlphaTest_Loader",
                    "DrawEllipse_Color_Loader", "DrawEllipse_ImagePattern_AlphaTest_Loader",
                    "DrawEllipse_ImagePattern_Loader", "DrawEllipse_LinearGradient_PAD_AlphaTest_Loader",
                    "DrawEllipse_LinearGradient_PAD_Loader", "DrawEllipse_LinearGradient_REFLECT_AlphaTest_Loader",
                    "DrawEllipse_LinearGradient_REFLECT_Loader",
                    "DrawEllipse_LinearGradient_REPEAT_AlphaTest_Loader",
                    "DrawEllipse_LinearGradient_REPEAT_Loader", "DrawEllipse_RadialGradient_PAD_AlphaTest_Loader",
                    "DrawEllipse_RadialGradient_PAD_Loader", "DrawEllipse_RadialGradient_REFLECT_AlphaTest_Loader",
                    "DrawEllipse_RadialGradient_REFLECT_Loader",
                    "DrawEllipse_RadialGradient_REPEAT_AlphaTest_Loader",
                    "DrawEllipse_RadialGradient_REPEAT_Loader", "DrawPgram_Color_AlphaTest_Loader",
                    "DrawPgram_Color_Loader", "DrawPgram_ImagePattern_AlphaTest_Loader",
                    "DrawPgram_ImagePattern_Loader", "DrawPgram_LinearGradient_PAD_AlphaTest_Loader",
                    "DrawPgram_LinearGradient_PAD_Loader", "DrawPgram_LinearGradient_REFLECT_AlphaTest_Loader",
                    "DrawPgram_LinearGradient_REFLECT_Loader", "DrawPgram_LinearGradient_REPEAT_AlphaTest_Loader",
                    "DrawPgram_LinearGradient_REPEAT_Loader", "DrawPgram_RadialGradient_PAD_AlphaTest_Loader",
                    "DrawPgram_RadialGradient_PAD_Loader", "DrawPgram_RadialGradient_REFLECT_AlphaTest_Loader",
                    "DrawPgram_RadialGradient_REFLECT_Loader", "DrawPgram_RadialGradient_REPEAT_AlphaTest_Loader",
                    "DrawPgram_RadialGradient_REPEAT_Loader", "DrawRoundRect_Color_AlphaTest_Loader",
                    "DrawRoundRect_Color_Loader", "DrawRoundRect_ImagePattern_AlphaTest_Loader",
                    "DrawRoundRect_ImagePattern_Loader", "DrawRoundRect_LinearGradient_PAD_AlphaTest_Loader",
                    "DrawRoundRect_LinearGradient_PAD_Loader",
                    "DrawRoundRect_LinearGradient_REFLECT_AlphaTest_Loader",
                    "DrawRoundRect_LinearGradient_REFLECT_Loader",
                    "DrawRoundRect_LinearGradient_REPEAT_AlphaTest_Loader",
                    "DrawRoundRect_LinearGradient_REPEAT_Loader",
                    "DrawRoundRect_RadialGradient_PAD_AlphaTest_Loader", "DrawRoundRect_RadialGradient_PAD_Loader",
                    "DrawRoundRect_RadialGradient_REFLECT_AlphaTest_Loader",
                    "DrawRoundRect_RadialGradient_REFLECT_Loader",
                    "DrawRoundRect_RadialGradient_REPEAT_AlphaTest_Loader",
                    "DrawRoundRect_RadialGradient_REPEAT_Loader", "DrawSemiRoundRect_Color_AlphaTest_Loader",
                    "DrawSemiRoundRect_Color_Loader", "DrawSemiRoundRect_ImagePattern_AlphaTest_Loader",
                    "DrawSemiRoundRect_ImagePattern_Loader",
                    "DrawSemiRoundRect_LinearGradient_PAD_AlphaTest_Loader",
                    "DrawSemiRoundRect_LinearGradient_PAD_Loader",
                    "DrawSemiRoundRect_LinearGradient_REFLECT_AlphaTest_Loader",
                    "DrawSemiRoundRect_LinearGradient_REFLECT_Loader",
                    "DrawSemiRoundRect_LinearGradient_REPEAT_AlphaTest_Loader",
                    "DrawSemiRoundRect_LinearGradient_REPEAT_Loader",
                    "DrawSemiRoundRect_RadialGradient_PAD_AlphaTest_Loader",
                    "DrawSemiRoundRect_RadialGradient_PAD_Loader",
                    "DrawSemiRoundRect_RadialGradient_REFLECT_AlphaTest_Loader",
                    "DrawSemiRoundRect_RadialGradient_REFLECT_Loader",
                    "DrawSemiRoundRect_RadialGradient_REPEAT_AlphaTest_Loader",
                    "DrawSemiRoundRect_RadialGradient_REPEAT_Loader", "FillCircle_Color_AlphaTest_Loader",
                    "FillCircle_Color_Loader", "FillCircle_ImagePattern_AlphaTest_Loader",
                    "FillCircle_ImagePattern_Loader", "FillCircle_LinearGradient_PAD_AlphaTest_Loader",
                    "FillCircle_LinearGradient_PAD_Loader", "FillCircle_LinearGradient_REFLECT_AlphaTest_Loader",
                    "FillCircle_LinearGradient_REFLECT_Loader", "FillCircle_LinearGradient_REPEAT_AlphaTest_Loader",
                    "FillCircle_LinearGradient_REPEAT_Loader", "FillCircle_RadialGradient_PAD_AlphaTest_Loader",
                    "FillCircle_RadialGradient_PAD_Loader", "FillCircle_RadialGradient_REFLECT_AlphaTest_Loader",
                    "FillCircle_RadialGradient_REFLECT_Loader", "FillCircle_RadialGradient_REPEAT_AlphaTest_Loader",
                    "FillCircle_RadialGradient_REPEAT_Loader", "FillEllipse_Color_AlphaTest_Loader",
                    "FillEllipse_Color_Loader", "FillEllipse_ImagePattern_AlphaTest_Loader",
                    "FillEllipse_ImagePattern_Loader", "FillEllipse_LinearGradient_PAD_AlphaTest_Loader",
                    "FillEllipse_LinearGradient_PAD_Loader", "FillEllipse_LinearGradient_REFLECT_AlphaTest_Loader",
                    "FillEllipse_LinearGradient_REFLECT_Loader",
                    "FillEllipse_LinearGradient_REPEAT_AlphaTest_Loader",
                    "FillEllipse_LinearGradient_REPEAT_Loader", "FillEllipse_RadialGradient_PAD_AlphaTest_Loader",
                    "FillEllipse_RadialGradient_PAD_Loader", "FillEllipse_RadialGradient_REFLECT_AlphaTest_Loader",
                    "FillEllipse_RadialGradient_REFLECT_Loader",
                    "FillEllipse_RadialGradient_REPEAT_AlphaTest_Loader",
                    "FillEllipse_RadialGradient_REPEAT_Loader", "FillPgram_Color_AlphaTest_Loader",
                    "FillPgram_Color_Loader", "FillPgram_ImagePattern_AlphaTest_Loader",
                    "FillPgram_ImagePattern_Loader", "FillPgram_LinearGradient_PAD_AlphaTest_Loader",
                    "FillPgram_LinearGradient_PAD_Loader", "FillPgram_LinearGradient_REFLECT_AlphaTest_Loader",
                    "FillPgram_LinearGradient_REFLECT_Loader", "FillPgram_LinearGradient_REPEAT_AlphaTest_Loader",
                    "FillPgram_LinearGradient_REPEAT_Loader", "FillPgram_RadialGradient_PAD_AlphaTest_Loader",
                    "FillPgram_RadialGradient_PAD_Loader", "FillPgram_RadialGradient_REFLECT_AlphaTest_Loader",
                    "FillPgram_RadialGradient_REFLECT_Loader", "FillPgram_RadialGradient_REPEAT_AlphaTest_Loader",
                    "FillPgram_RadialGradient_REPEAT_Loader", "FillRoundRect_Color_AlphaTest_Loader",
                    "FillRoundRect_Color_Loader", "FillRoundRect_ImagePattern_AlphaTest_Loader",
                    "FillRoundRect_ImagePattern_Loader", "FillRoundRect_LinearGradient_PAD_AlphaTest_Loader",
                    "FillRoundRect_LinearGradient_PAD_Loader",
                    "FillRoundRect_LinearGradient_REFLECT_AlphaTest_Loader",
                    "FillRoundRect_LinearGradient_REFLECT_Loader",
                    "FillRoundRect_LinearGradient_REPEAT_AlphaTest_Loader",
                    "FillRoundRect_LinearGradient_REPEAT_Loader",
                    "FillRoundRect_RadialGradient_PAD_AlphaTest_Loader", "FillRoundRect_RadialGradient_PAD_Loader",
                    "FillRoundRect_RadialGradient_REFLECT_AlphaTest_Loader",
                    "FillRoundRect_RadialGradient_REFLECT_Loader",
                    "FillRoundRect_RadialGradient_REPEAT_AlphaTest_Loader",
                    "FillRoundRect_RadialGradient_REPEAT_Loader", "Mask_TextureRGB_AlphaTest_Loader",
                    "Mask_TextureRGB_Loader", "Mask_TextureSuper_AlphaTest_Loader", "Mask_TextureSuper_Loader",
                    "Solid_Color_AlphaTest_Loader", "Solid_Color_Loader", "Solid_ImagePattern_AlphaTest_Loader",
                    "Solid_ImagePattern_Loader", "Solid_LinearGradient_PAD_AlphaTest_Loader",
                    "Solid_LinearGradient_PAD_Loader", "Solid_LinearGradient_REFLECT_AlphaTest_Loader",
                    "Solid_LinearGradient_REFLECT_Loader", "Solid_LinearGradient_REPEAT_AlphaTest_Loader",
                    "Solid_LinearGradient_REPEAT_Loader", "Solid_RadialGradient_PAD_AlphaTest_Loader",
                    "Solid_RadialGradient_PAD_Loader", "Solid_RadialGradient_REFLECT_AlphaTest_Loader",
                    "Solid_RadialGradient_REFLECT_Loader", "Solid_RadialGradient_REPEAT_AlphaTest_Loader",
                    "Solid_RadialGradient_REPEAT_Loader", "Solid_TextureFirstPassLCD_AlphaTest_Loader",
                    "Solid_TextureFirstPassLCD_Loader", "Solid_TextureRGB_AlphaTest_Loader",
                    "Solid_TextureRGB_Loader", "Solid_TextureSecondPassLCD_AlphaTest_Loader",
                    "Solid_TextureSecondPassLCD_Loader", "Solid_TextureYV12_AlphaTest_Loader",
                    "Solid_TextureYV12_Loader", "Texture_Color_AlphaTest_Loader", "Texture_Color_Loader",
                    "Texture_ImagePattern_AlphaTest_Loader", "Texture_ImagePattern_Loader",
                    "Texture_LinearGradient_PAD_AlphaTest_Loader", "Texture_LinearGradient_PAD_Loader",
                    "Texture_LinearGradient_REFLECT_AlphaTest_Loader", "Texture_LinearGradient_REFLECT_Loader",
                    "Texture_LinearGradient_REPEAT_AlphaTest_Loader", "Texture_LinearGradient_REPEAT_Loader",
                    "Texture_RadialGradient_PAD_AlphaTest_Loader", "Texture_RadialGradient_PAD_Loader",
                    "Texture_RadialGradient_REFLECT_AlphaTest_Loader", "Texture_RadialGradient_REFLECT_Loader",
                    "Texture_RadialGradient_REPEAT_AlphaTest_Loader", "Texture_RadialGradient_REPEAT_Loader");

    /// The effects pipeline resolves a peer per renderer - hand-written Java, SSE intrinsics,
    /// Prism shaders, Metal - by name, for the same reason and with the same blind spot. Reduced
    /// opacity on a disabled control is enough to pull one of these in, which is what the
    /// `-Dsmoke.test` toggling in [StageInitializer] is there to exercise.
    private static final List<String> EFFECT_PEERS = types(
            in("com.sun.scenario.effect.impl.es2",
                    "ES2ShaderSource"),
            in("com.sun.scenario.effect.impl.hw.mtl",
                    "MTLShaderSource"),
            in("com.sun.scenario.effect.impl.prism",
                    "PrRenderer"),
            in("com.sun.scenario.effect.impl.prism.ps",
                    "PPSBlend_ADDPeer", "PPSBlend_BLUEPeer", "PPSBlend_COLOR_BURNPeer",
                    "PPSBlend_COLOR_DODGEPeer", "PPSBlend_DARKENPeer", "PPSBlend_DIFFERENCEPeer",
                    "PPSBlend_EXCLUSIONPeer", "PPSBlend_GREENPeer", "PPSBlend_HARD_LIGHTPeer",
                    "PPSBlend_LIGHTENPeer", "PPSBlend_MULTIPLYPeer", "PPSBlend_OVERLAYPeer", "PPSBlend_REDPeer",
                    "PPSBlend_SCREENPeer", "PPSBlend_SOFT_LIGHTPeer", "PPSBlend_SRC_ATOPPeer",
                    "PPSBlend_SRC_INPeer", "PPSBlend_SRC_OUTPeer", "PPSBlend_SRC_OVERPeer", "PPSBrightpassPeer",
                    "PPSColorAdjustPeer", "PPSDisplacementMapPeer", "PPSEffectPeer", "PPSInvertMaskPeer",
                    "PPSLinearConvolvePeer", "PPSLinearConvolveShadowPeer", "PPSOneSamplerPeer",
                    "PPSPerspectiveTransformPeer", "PPSPhongLighting_DISTANTPeer", "PPSPhongLighting_POINTPeer",
                    "PPSPhongLighting_SPOTPeer", "PPSRenderer", "PPSSepiaTonePeer", "PPSTwoSamplerPeer",
                    "PPSZeroSamplerPeer", "PPStoPSWDisplacementMapPeer"),
            in("com.sun.scenario.effect.impl.prism.sw",
                    "PSWRenderer"),
            in("com.sun.scenario.effect.impl.sw.java",
                    "JSWBlend_ADDPeer", "JSWBlend_BLUEPeer", "JSWBlend_COLOR_BURNPeer",
                    "JSWBlend_COLOR_DODGEPeer", "JSWBlend_DARKENPeer", "JSWBlend_DIFFERENCEPeer",
                    "JSWBlend_EXCLUSIONPeer", "JSWBlend_GREENPeer", "JSWBlend_HARD_LIGHTPeer",
                    "JSWBlend_LIGHTENPeer", "JSWBlend_MULTIPLYPeer", "JSWBlend_OVERLAYPeer", "JSWBlend_REDPeer",
                    "JSWBlend_SCREENPeer", "JSWBlend_SOFT_LIGHTPeer", "JSWBlend_SRC_ATOPPeer",
                    "JSWBlend_SRC_INPeer", "JSWBlend_SRC_OUTPeer", "JSWBlend_SRC_OVERPeer", "JSWBoxBlurPeer",
                    "JSWBoxShadowPeer", "JSWBrightpassPeer", "JSWColorAdjustPeer", "JSWDisplacementMapPeer",
                    "JSWEffectPeer", "JSWInvertMaskPeer", "JSWLinearConvolvePeer",
                    "JSWLinearConvolveShadowPeer", "JSWPerspectiveTransformPeer",
                    "JSWPhongLighting_DISTANTPeer", "JSWPhongLighting_POINTPeer", "JSWPhongLighting_SPOTPeer",
                    "JSWRendererDelegate", "JSWSepiaTonePeer"),
            in("com.sun.scenario.effect.impl.sw.sse",
                    "SSEBlend_ADDPeer", "SSEBlend_BLUEPeer", "SSEBlend_COLOR_BURNPeer",
                    "SSEBlend_COLOR_DODGEPeer", "SSEBlend_DARKENPeer", "SSEBlend_DIFFERENCEPeer",
                    "SSEBlend_EXCLUSIONPeer", "SSEBlend_GREENPeer", "SSEBlend_HARD_LIGHTPeer",
                    "SSEBlend_LIGHTENPeer", "SSEBlend_MULTIPLYPeer", "SSEBlend_OVERLAYPeer", "SSEBlend_REDPeer",
                    "SSEBlend_SCREENPeer", "SSEBlend_SOFT_LIGHTPeer", "SSEBlend_SRC_ATOPPeer",
                    "SSEBlend_SRC_INPeer", "SSEBlend_SRC_OUTPeer", "SSEBlend_SRC_OVERPeer", "SSEBoxBlurPeer",
                    "SSEBoxShadowPeer", "SSEBrightpassPeer", "SSEColorAdjustPeer", "SSEDisplacementMapPeer",
                    "SSEEffectPeer", "SSEInvertMaskPeer", "SSELinearConvolvePeer",
                    "SSELinearConvolveShadowPeer", "SSEPerspectiveTransformPeer",
                    "SSEPhongLighting_DISTANTPeer", "SSEPhongLighting_POINTPeer", "SSEPhongLighting_SPOTPeer",
                    "SSERendererDelegate", "SSESepiaTonePeer")
    );

    /// The CSS engine turns a selector into a class: `styles.css` naming `.greeting` sends it
    /// looking for the Java type behind the styleable, and property lookups on the way to a
    /// computed value go through reflection too.
    private static final List<String> PUBLIC_API = types(
            in("javafx.animation",
                    "Interpolator"),
            in("javafx.application",
                    "Platform"),
            in("javafx.collections",
                    "ObservableList"),
            in("javafx.css",
                    "CssParser", "Rule"),
            in("javafx.event",
                    "ActionEvent", "Event", "EventHandler"),
            in("javafx.geometry",
                    "Insets", "Pos"),
            in("javafx.scene",
                    "Camera", "Group", "Node", "ParallelCamera", "Parent", "Scene"),
            in("javafx.scene.control",
                    "Button", "Control", "Label", "Labeled", "TextArea"),
            in("javafx.scene.effect",
                    "Effect"),
            in("javafx.scene.image",
                    "Image"),
            in("javafx.scene.layout",
                    "HBox", "Pane", "Region", "VBox"),
            in("javafx.scene.paint",
                    "Color[]"),
            in("javafx.scene.shape",
                    "Path", "PathElement", "Rectangle", "SVGPath", "Shape"),
            in("javafx.scene.text",
                    "Font", "Text"),
            in("javafx.scene.transform",
                    "Transform"),
            in("javafx.stage",
                    "PopupWindow", "Stage", "Window", "WindowEvent")
    );

    /// The rest of the toolkit's own by-name plumbing: the pipeline and font factory it selects
    /// from a system property, the logger it picks depending on whether JFR is around.
    private static final List<String> TOOLKIT = types(
            in("com.sun.glass.ui",
                    "Screen[]"),
            in("com.sun.javafx",
                    "PreviewFeature"),
            in("com.sun.javafx.font.coretext",
                    "CTFactory"),
            in("com.sun.javafx.logging",
                    "PrintLogger"),
            in("com.sun.javafx.logging.jfr",
                    "JFRPulseLogger"),
            in("com.sun.javafx.scene.control.skin",
                    "Utils"),
            in("com.sun.javafx.tk.quantum",
                    "QuantumToolkit"),
            in("com.sun.prism",
                    "GraphicsPipeline"),
            in("com.sun.prism.es2",
                    "ES2Pipeline", "MacGLFactory")
    );

    /// Resources JavaFX loads off the classpath: the native libraries it unpacks and dlopen()s,
    /// the Modena and Caspian stylesheets, the shader programs, and the control skins'
    /// localized strings. Plus this application's own `styles.css`.
    private static final List<String> RESOURCES = List.of(
            "*.dylib",
            "com/sun/glass/utils/NativeLibLoader.class",
            "com/sun/javafx/scene/control/skin/modena/**",
            "com/sun/javafx/scene/control/skin/caspian/**",
            "com/sun/javafx/scene/control/skin/resources/*.properties",
            "com/sun/javafx/tk/quantum/*.properties",
            "com/sun/prism/es2/glsl/**",
            "com/sun/prism/mtl/msl/**",
            "com/sun/scenario/effect/impl/es2/glsl/**",
            "styles.css");

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        reflective().forEach(type -> hints.reflection().registerTypeIfPresent(classLoader, type, EVERYTHING));
        NATIVE_CALLBACKS.forEach(type -> hints.jni().registerTypeIfPresent(classLoader, type, EVERYTHING));
        RESOURCES.forEach(hints.resources()::registerPattern);
    }

    /// Everything wants reflection, the JNI-reachable types included - JNI registration is on top
    /// of that, not instead of it.
    private static List<String> reflective() {
        return types(NATIVE_CALLBACKS, PRISM_SHADERS, EFFECT_PEERS, PUBLIC_API, TOOLKIT);
    }

    private static boolean isDeprecated(MemberCategory category) {
        try {
            return MemberCategory.class.getField(category.name()).isAnnotationPresent(Deprecated.class);
        }
        catch (NoSuchFieldException noSuchField) {
            throw new IllegalStateException(noSuchField);
        }
    }

    /// The lists above are long enough without repeating the package on every line.
    private static List<String> in(String packageName, String... simpleNames) {
        return Stream.of(simpleNames).map(simpleName -> packageName + "." + simpleName).toList();
    }

    @SafeVarargs
    private static List<String> types(List<String>... groups) {
        return Stream.of(groups).flatMap(List::stream).toList();
    }

}
