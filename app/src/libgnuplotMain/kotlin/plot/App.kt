package plot

import kotlin.native.concurrent.*
import kotlinx.cinterop.*
import platform.posix.sleep
import kotlin.text.*
import openal.*
import mgl.*
import gtk4.*
import synth.*
import kotlin.math.*

var dev: CPointer<ALCdevice>? = null
var ctx: CPointer<ALCcontext>? = null
var app:CPointer<GtkApplication>? = null
var keys: CPointer<GObject>? = null
var ui_step_math: CPointer<GObject>? = null
var ui_sound_math: CPointer<GObject>? = null
var ui_envelop1_math: CPointer<GObject>? = null
var ui_envelop2_math: CPointer<GObject>? = null
var ui_envelop3_math: CPointer<GObject>? = null
var ui_envelop4_math: CPointer<GObject>? = null
var ui_final_math: CPointer<GObject>? = null
val keyStates: HashMap<UInt, Boolean> = HashMap<UInt, Boolean>()

@ThreadLocal
val sounds: Array<Int> = arrayOf<Int>(122, 115, 120, 100, 99, 103)

data class MathParam(
                 val step: String, 
                 val sound: String, 
                 val envelop1: String,
                 val envelop2: String,
                 val envelop3: String,
                 val envelop4: String,
                 val finalMath: String,
                 val key:Int
)

fun sound_thread(mathParam: MathParam) = memScoped {
  initRuntimeIfNeeded()
  
    var key2 = sounds.indexOf(mathParam.key)
    val key = "${key2}".toInt()
    
    var buf = allocArray<ALuintVar>(1)
    alGenBuffers(1, buf)
  
    var d = 1.0f;
    var sr = 44100;
    var y = mgl_create_data_size(sr.toInt(),1,0)
    
    var step = mathParam.step.replace(oldValue= "\${key}", newValue = key.toString())
    var sound = mathParam.sound.replace(oldValue= "\${step}", newValue = step)
    var envelop1 = mathParam.envelop1
    var envelop2 = mathParam.envelop2
    var finalMath = mathParam.finalMath
    .replace(oldValue= "\${sound}", newValue = sound)
    .replace(oldValue= "\${envelop1}", newValue = envelop1)
    .replace(oldValue= "\${envelop2}", newValue = envelop2)
    
    mgl_data_modify(y, finalMath ,0);
        
    var samples = ShortArray(sr.toInt())
    var j = 0.0
    for(i in 0..sr-1) {
       samples[i] = mgl_data_get_value(y,i,0,0).toInt().toShort()
    }
  
    samples.usePinned {
       alBufferData(buf[0], AL_FORMAT_MONO16, it.addressOf(0), (sr * sizeOf<ShortVar>()).toInt(), sr.toInt())
    }
  
    var src = allocArray<ALuintVar>(1)
    alGenSources(1.toInt(), src)
    alSourcei(src[0], AL_BUFFER, buf[0].toInt())
    
    alSourcePlay(src[0])
  
    sleep(d.toUInt())
  
    alSourcei(src[0], AL_BUFFER, 0)
    alDeleteSources(1, src)
    alDeleteBuffers(1, buf)
}

fun realize_callback(
                 widget:CPointer<GtkWidget>?
) {
    println("realize_callback")
}

fun render_callback(
                 glarea:CPointer<GtkDrawingArea>?, 
                 cr:CPointer<cairo_t>?
) = memScoped {

    println("render_callback")
    var csurface = cairo_get_target(cr)

    var error = alloc<CPointerVar<GError>>()
    var handle = rsvg_handle_new_from_file ("svg/key_white.svg", error.ptr);
    if(error.value!=null)
        throw Error("unable to process key_white.svg " + error.value)
    if(handle==null)
        throw Error("unable to load key_white.svg")
   
var style = """
.wbutton {
  fill:#ffffff;fill-opacity:1;stroke:#000000;stroke-opacity:1;stroke-width:0.26458334;stroke-miterlimit:4;stroke-dasharray:none
}
"""

for ((key, value) in keyStates) {
    if(value) {
    var zkey = key.toString()
    if(zkey.length==1) { zkey = "00"+zkey }
    if(zkey.length==2) { zkey = "0"+zkey }

style = style + """

#rect${zkey} {
  fill:#B0C4DE;fill-opacity:1;stroke:#000000;stroke-opacity:1;stroke-width:0.26458334;stroke-miterlimit:4;stroke-dasharray:none
}

"""
    }
}
    val bytes = style.toCharArray()
        
    var styleObj = allocArray<guint8Var>(bytes.size).apply {
        for(i in 0..bytes.size-1) {
            this[i] = bytes[i].code.toUByte()
        }
    }
    
    rsvg_handle_set_stylesheet (handle, styleObj, 
                            bytes.size.toULong(),
                            error.ptr);
    if(error.value!=null)
        throw Error("unable to process css for svg")

    if( rsvg_handle_render_cairo ( handle, cr ) != 1 )
        throw Error( "Drawing failed" )  
}

fun key_pressed (
                 controller: CPointer<GtkEventController>,
                 keyval: guint,
                 keycode: guint,
                 modifiers: GdkModifierType,
                 entry: CPointer<GtkEntry>
):gboolean {
    val isKey = keyStates.get(keyval)
    
    if(!(isKey==true) 
        && sounds.indexOf(keyval.toInt())!=-1 
        && sounds.indexOf(keyval.toInt())<6){
        
        val mathParam = MathParam (
            gtk_entry_buffer_get_text(
              gtk_entry_get_buffer(ui_step_math!!.reinterpret())
            )!!.toKString(),
            gtk_entry_buffer_get_text(
              gtk_entry_get_buffer(ui_sound_math!!.reinterpret())
            )!!.toKString(),
            gtk_entry_buffer_get_text(
              gtk_entry_get_buffer(ui_envelop1_math!!.reinterpret())
            )!!.toKString(),
            gtk_entry_buffer_get_text(
              gtk_entry_get_buffer(ui_envelop2_math!!.reinterpret())
            )!!.toKString(),
            gtk_entry_buffer_get_text(
              gtk_entry_get_buffer(ui_envelop3_math!!.reinterpret())
            )!!.toKString(),
            gtk_entry_buffer_get_text(
              gtk_entry_get_buffer(ui_envelop4_math!!.reinterpret())
            )!!.toKString(),
            gtk_entry_buffer_get_text(
              gtk_entry_get_buffer(ui_final_math!!.reinterpret())
            )!!.toKString(),
            keyval.toInt()
        )
        
        val worker = Worker.start(true, "worker1")
        worker.execute(TransferMode.UNSAFE, { mathParam }) { data ->
            sound_thread(data)
            null
        }
    }
    
    keyStates.put(keyval,true)
    gtk_widget_queue_draw(keys!!.reinterpret())
    return GDK_EVENT_PROPAGATE
}

fun left_mouse_pressed (
                 gesture: CPointer<GtkGestureClick>,
                 n_press: Int,
                 x: Double,
                 y: Double,
                 widget: CPointer<GtkWidget>
):gboolean {
    println("${n_press},${x},${y}")
    gtk_widget_grab_focus(widget)
    return GDK_EVENT_STOP
}

fun key_released (
                 controller: CPointer<GtkEventController>,
                 keyval: guint,
                 keycode: guint,
                 modifiers: GdkModifierType,
                 entry: CPointer<GtkEntry>
):gboolean {
    keyStates.put(keyval,false)
    gtk_widget_queue_draw(keys!!.reinterpret())
    return GDK_EVENT_PROPAGATE
}

fun toggle_edit(togglebutton: CPointer<GtkToggleButton>) {
    var button_state = gtk_toggle_button_get_active(togglebutton);
    
    gtk_widget_set_focusable(ui_step_math!!.reinterpret(), button_state)
    gtk_widget_set_can_focus(ui_step_math!!.reinterpret(), button_state)
    gtk_editable_set_editable(ui_step_math!!.reinterpret(), button_state)
    
    gtk_widget_set_focusable(ui_sound_math!!.reinterpret(), button_state)
    gtk_widget_set_can_focus(ui_sound_math!!.reinterpret(), button_state)
    gtk_editable_set_editable(ui_sound_math!!.reinterpret(), button_state)
    
    gtk_widget_set_focusable(ui_envelop1_math!!.reinterpret(), button_state)
    gtk_widget_set_can_focus(ui_envelop1_math!!.reinterpret(), button_state)
    gtk_editable_set_editable(ui_envelop1_math!!.reinterpret(), button_state)
    
    gtk_widget_set_focusable(ui_envelop2_math!!.reinterpret(), button_state)
    gtk_widget_set_can_focus(ui_envelop2_math!!.reinterpret(), button_state)
    gtk_editable_set_editable(ui_envelop2_math!!.reinterpret(), button_state)
    
    gtk_widget_set_focusable(ui_envelop3_math!!.reinterpret(), button_state)
    gtk_widget_set_can_focus(ui_envelop3_math!!.reinterpret(), button_state)
    gtk_editable_set_editable(ui_envelop3_math!!.reinterpret(), button_state)
    
    gtk_widget_set_focusable(ui_envelop4_math!!.reinterpret(), button_state)
    gtk_widget_set_can_focus(ui_envelop4_math!!.reinterpret(), button_state)
    gtk_editable_set_editable(ui_envelop4_math!!.reinterpret(), button_state)
    
    gtk_widget_set_focusable(ui_final_math!!.reinterpret(), button_state)
    gtk_widget_set_can_focus(ui_final_math!!.reinterpret(), button_state)
    gtk_editable_set_editable(ui_final_math!!.reinterpret(), button_state)
}

fun math_edit_toggled(
                 togglebutton: CPointer<GtkToggleButton>, 
                 text_label: CPointer<GtkLabel>
)
{
    toggle_edit(togglebutton)
}

fun activate_callback(app:CPointer<GtkApplication>?) {
    println("activate")
    
    var builder = gtk_builder_new_from_file ("glade/window_main.glade")
    var window = gtk_builder_get_object(builder, "window_main")
    gtk_window_set_application (window!!.reinterpret(), app)

    var provider = gtk_css_provider_new();
    gtk_css_provider_load_from_path(provider, "css/theme.css");
    gtk_style_context_add_provider_for_display(
                               gtk_widget_get_display(window.reinterpret()),
                               provider!!.reinterpret(),
                               GTK_STYLE_PROVIDER_PRIORITY_USER);
    keys = gtk_builder_get_object(builder, "keyboard_keys")
    ui_step_math = gtk_builder_get_object(builder, "step_math")
    ui_sound_math = gtk_builder_get_object(builder, "sound_math")
    ui_envelop1_math = gtk_builder_get_object(builder, "envelop1_math")
    ui_envelop2_math = gtk_builder_get_object(builder, "envelop2_math")
    ui_envelop3_math = gtk_builder_get_object(builder, "envelop3_math")
    ui_envelop4_math = gtk_builder_get_object(builder, "envelop4_math")
    ui_final_math = gtk_builder_get_object(builder, "final_math")
    var ui_math_toggle = gtk_builder_get_object(builder, "math_toggle")
    toggle_edit(ui_math_toggle!!.reinterpret())
    
    gtk_drawing_area_set_draw_func(
        keys!!.reinterpret(),
        staticCFunction {
            glarea:CPointer<GtkDrawingArea>?, 
            cr:CPointer<cairo_t>?
            -> render_callback ( glarea, cr )
        }.reinterpret(),
        null, 
        null
    )
    
    var keyboard_controller = gtk_event_controller_key_new()
    var focus_controller = gtk_event_controller_focus_new()
    var motion_controller = gtk_gesture_click_new()
    gtk_gesture_single_set_button (motion_controller!!.reinterpret(), 1);
    
    g_signal_connect_data (
        ui_math_toggle!!.reinterpret(), 
        "toggled", 
        staticCFunction {
            togglebutton: CPointer<GtkToggleButton>, 
            text_label: CPointer<GtkLabel>
            -> math_edit_toggled(togglebutton, text_label)
        }.reinterpret(),
        ui_math_toggle!!.reinterpret(), 
        null, 
        0u
    )
    
    g_object_set_data_full (
        window!!.reinterpret(), 
        "controller", 
        g_object_ref(keyboard_controller!!.reinterpret()), 
        staticCFunction {
            obj: gpointer? -> g_object_unref(obj)
        }.reinterpret()
    )
    
    g_object_set_data_full (
        keys!!.reinterpret(), 
        "controller", 
        g_object_ref(focus_controller!!.reinterpret()), 
        staticCFunction {
            obj: gpointer? -> g_object_unref(obj)
        }.reinterpret()
    )
    
    g_object_set_data_full (
        keys!!.reinterpret(), 
        "controller", 
        g_object_ref(motion_controller!!.reinterpret()), 
        staticCFunction {
            obj: gpointer? -> g_object_unref(obj)
        }.reinterpret()
    )
    
    g_signal_connect_data (
        keyboard_controller!!.reinterpret(), 
        "key-pressed", 
        staticCFunction {
             controller: CPointer<GtkEventController>,
             keyval: guint,
             keycode: guint,
             modifiers: GdkModifierType,
             entry: CPointer<GtkEntry>
             -> key_pressed (
                controller, 
                keyval,
                keycode,
                modifiers,
                entry
             )
        }.reinterpret(), 
        keys!!.reinterpret(), 
        null, 
        0u
    )
    
    g_signal_connect_data (
        keyboard_controller!!.reinterpret(), 
        "key-released", 
        staticCFunction {
             controller: CPointer<GtkEventController>,
             keyval: guint,
             keycode: guint,
             modifiers: GdkModifierType,
             entry: CPointer<GtkEntry>
             -> key_released (
                controller, 
                keyval,
                keycode,
                modifiers,
                entry
             )
        }.reinterpret(), 
        keys!!.reinterpret(), 
        null, 
        0u
    )
    
    g_signal_connect_data (
        motion_controller!!.reinterpret(), 
        "pressed", 
        staticCFunction {
             gesture: CPointer<GtkGestureClick>,
             n_press: Int,
             x: Double,
             y: Double,
             widget: CPointer<GtkWidget>
             -> left_mouse_pressed(gesture, n_press, x, y, widget)
        }.reinterpret(), 
        keys!!.reinterpret(), 
        null, 
        0u
    )
    
    gtk_widget_add_controller (
        window!!.reinterpret(), 
        keyboard_controller!!.reinterpret()
    )
    
    gtk_widget_add_controller (
        keys!!.reinterpret(), 
        focus_controller!!.reinterpret()
    )
    
    gtk_widget_add_controller (
        keys!!.reinterpret(), 
        motion_controller!!.reinterpret()
    )
    
    g_object_unref(builder)
    gtk_widget_show (window.reinterpret())
}

fun startup_callback(app:CPointer<GtkApplication>?) {
    println("startup")
    
    var defname = alcGetString(null, ALC_DEFAULT_DEVICE_SPECIFIER)
    dev = alcOpenDevice(defname?.toKString())
    ctx = alcCreateContext(dev, null)
    alcMakeContextCurrent(ctx)

}

fun shutdown_callback(app:CPointer<GtkApplication>?) {
    println("shutdown")
    
    alcMakeContextCurrent(null)
    alcDestroyContext(ctx)
    alcCloseDevice(dev)

}

fun main() {
    app = gtk_application_new ("org.gtk.example", G_APPLICATION_FLAGS_NONE)
    
    g_signal_connect_data(
        app!!.reinterpret(), 
        "activate", 
        staticCFunction {  app:CPointer<GtkApplication>?
            -> activate_callback(app)
        }.reinterpret(), 
        null, 
        null, 
        0u
    )
    
    g_signal_connect_data(
        app!!.reinterpret(), 
        "startup", 
        staticCFunction {  app:CPointer<GtkApplication>?
            -> startup_callback(app)
        }.reinterpret(), 
        null, 
        null, 
        0u
    )
    
    g_signal_connect_data(
        app!!.reinterpret(), 
        "shutdown", 
        staticCFunction {  app:CPointer<GtkApplication>?
            -> shutdown_callback(app)
        }.reinterpret(), 
        null, 
        null, 
        0u
    )
    
    run_app(app, 0, null)
    g_object_unref (app)
    
}