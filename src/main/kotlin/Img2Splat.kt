import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.cli.required
import java.awt.image.BufferedImage
import java.io.File
import java.math.BigDecimal
import javax.imageio.ImageIO

const val WIDTH = 320
const val HEIGHT = 120
const val MACRO_FILENAME = "splat_macro.txt"
const val PREVIEW_FILENAME = "macro_preview.png"
const val PREVIEW_TYPE = "png"
const val DEFAULT_PRESS_DURATION = "0.1"
val MACRO_START_DELAY = BigDecimal("3.0")

enum class Button(val macroToken: String) {
    DOWN("DPAD_DOWN"),
    LEFT("DPAD_LEFT"),
    RIGHT("DPAD_RIGHT"),
    A("A"),
    HOME("HOME"),
    MINUS("MINUS"),
}

data class Command(
    val button: Button?, // NXBT also supports multiple buttons at once, but we're only ever pressing one
    val duration: BigDecimal
) {
    override fun toString() = if (button == null) {
        "${duration}s"
    } else {
        "${button.macroToken} ${duration}s"
    }
}

class MacroBuilder(private val defaultPressDuration: BigDecimal) {
    private val _commands: MutableList<Command> = mutableListOf()
    val commands: List<Command> = _commands

    fun addButtonPress(button: Button, duration: BigDecimal = defaultPressDuration) {
        _commands.add(Command(button, duration))
        addNeutral(duration)
    }

    fun addNeutral(duration: BigDecimal) {
        _commands.add(Command(null, duration))
    }
}

enum class HorizontalDirection(val moveButton: Button) {
    LEFT(Button.LEFT), RIGHT(Button.RIGHT)
}

fun main(args: Array<String>) {
    val parser = ArgParser("java -jar img2splat.jar")
    val filePath by parser.option(ArgType.String,
        fullName = "input",
        shortName = "i",
        description = "Input image path")
        .required()
    val durationInput by parser.option(
        ArgType.String,
        fullName = "pressDuration",
        shortName = "d",
        description = "Duration of button presses in seconds, e.g. 0.1"
    ).default(DEFAULT_PRESS_DURATION)
    val repairInput by parser.option(
        ArgType.String,
        fullName = "repairRows",
        shortName = "r",
        description = "Comma separated-list of image rows between 0 and ${HEIGHT - 1} inclusive to repair, e.g. 78,79,80",
    )

    parser.parse(args)

    val imgFile = File(filePath)
    val img: BufferedImage? = ImageIO.read(imgFile)

    if (img == null) {
        println("$filePath doesn't seem to be a valid image.")
        return
    }

    if (img.width != WIDTH || img.height != HEIGHT) {
        println("Image must be $WIDTH x $HEIGHT")
        return
    }

    val pressDuration = try {
        BigDecimal(durationInput)
    } catch (t: Throwable) {
        println("\"$durationInput\" is not a valid button press duration")
        return
    }

    if (pressDuration < BigDecimal.ZERO || pressDuration == BigDecimal.ZERO) {
        println("Button press duration should be positive and non-zero")
        return
    }

    val repairRows = repairInput?.split(',')
        ?.map {
            try { it.toInt() } catch (t: Throwable) {
                println("\"$it\" is not a valid repair row")
                return@main
            }
        }
        ?.onEach {
            if (it < 0 || it > HEIGHT - 1) {
                println("Repair rows should be between 0 and ${HEIGHT - 1} inclusive")
                return@main
            }
        } ?: (0 until HEIGHT).toList() // Default to "repairing" all the rows -- do the whole image

    val macro = MacroBuilder(pressDuration)
    var currentDirection: HorizontalDirection = HorizontalDirection.RIGHT

    macro.addNeutral(MACRO_START_DELAY)

    for (y in (0 until HEIGHT)) {
        if (!repairRows.contains(y)) {
            // If we're not supposed to repair this row, just continue down to the next one
            if (y < HEIGHT - 1) { // Don't go off the bottom edge
                macro.addButtonPress(Button.DOWN)
            }
            continue
        }
        val xRange = when (currentDirection) {
            HorizontalDirection.LEFT -> (WIDTH - 1) downTo 0
            HorizontalDirection.RIGHT -> 0 until WIDTH
        }
        for (x in xRange) {
            if (img.blackPixel(x, y)) {
                macro.addButtonPress(Button.A)
            }
            val shouldMoveHorizontally = when (currentDirection) {
                HorizontalDirection.LEFT -> x > 0
                HorizontalDirection.RIGHT -> x < (WIDTH - 1)
            }
            if (shouldMoveHorizontally) {
                macro.addButtonPress(currentDirection.moveButton)
            }
        }
        currentDirection = when (currentDirection) {
            HorizontalDirection.LEFT -> HorizontalDirection.RIGHT
            HorizontalDirection.RIGHT -> HorizontalDirection.LEFT
        }
        if (y < HEIGHT - 1) { // Don't go off the bottom edge
            macro.addButtonPress(Button.DOWN)
        }
    }
    macro.addButtonPress(Button.MINUS)
    macro.addNeutral(BigDecimal("5.0"))

    val out = File(MACRO_FILENAME).printWriter()
    out.use {
        macro.commands.forEach { command ->
            out.println(command)
        }
        out.flush()
    }
    generatePreview(macro.commands)
    println("Generated ${macro.commands.size} operations, nice!")
}

fun BufferedImage.blackPixel(x: Int, y: Int): Boolean {
    val color = getRGB(x, y)
    val r = color shr 16 and 0xFF
    val g = color shr 8 and 0xFF
    val b = color shr 0 and 0xFF
    val luminance = (r * 0.2126f + g * 0.7152f + b * 0.0722f) / 255
    return luminance < 0.5f
}

fun generatePreview(commands: List<Command>) {
    val img = BufferedImage(320, 120, BufferedImage.TYPE_INT_RGB)
    for (y in (0 until 120)) {
        for (x in (0 until 320)) {
            img.setRGB(x, y, 0xFF0000FF.toInt())
        }
    }
    var x = 0
    var y = 0
    img.setRGB(0, 0, 0xFFFFFFFF.toInt())
    commands.forEach { command ->
        when(command.button) {
            Button.DOWN -> {
                y += 1
                img.setRGB(x, y, 0xFFFFFFFF.toInt())
            }
            Button.LEFT -> {
                x -= 1
                img.setRGB(x, y, 0xFFFFFFFF.toInt())
            }
            Button.RIGHT -> {
                x += 1
                img.setRGB(x, y, 0xFFFFFFFF.toInt())
            }
            Button.A -> {
                img.setRGB(x, y, 0xFF000000.toInt())
            }
            Button.HOME -> Unit
            Button.MINUS -> Unit
            null -> Unit
        }
    }
    val outFile = File(PREVIEW_FILENAME)
    ImageIO.write(img, PREVIEW_TYPE, outFile)
}