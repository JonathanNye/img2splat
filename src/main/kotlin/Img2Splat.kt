import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import java.awt.image.BufferedImage
import java.io.File
import java.math.BigDecimal
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min

const val WIDTH = 320
const val HEIGHT = 120
const val MACRO_FILENAME = "splat_macro.txt"
const val PREVIEW_FILENAME = "macro_preview.png"
const val PREVIEW_TYPE = "png"
val DEFAULT_PRESS_DURATION = BigDecimal("0.1")
val MACRO_START_DELAY = BigDecimal("3.0")

enum class Button(val macroToken: String) {
    DOWN("DPAD_DOWN"),
    LEFT("DPAD_LEFT"),
    RIGHT("DPAD_RIGHT"),
    A("A"),
    HOME("HOME"),
    MINUS("MINUS"),
}

class Command(
    private val duration: BigDecimal,
    vararg val buttons: Button, // We can have zero, one, or many buttons pressed
) {
    override fun toString() = if (buttons.isEmpty()) {
        "${duration}s"
    } else {
        "${buttons.joinToString(separator = " ") { it.macroToken }} ${duration}s"
    }
}

class MacroBuilder(private val defaultPressDuration: BigDecimal) {
    private val _commands: MutableList<Command> = mutableListOf()
    val commands: List<Command> = _commands

    fun addButtonPress(vararg button: Button, duration: BigDecimal = defaultPressDuration) {
        _commands.add(Command(duration, *button))
        addNeutral(duration)
    }

    fun addNeutral(duration: BigDecimal = defaultPressDuration) {
        _commands.add(Command(duration))
    }

    fun addRunLengthEncodedLine(encodedLine: RunLengthEncodedLine, duration: BigDecimal = defaultPressDuration) {
        val moveButton = encodedLine.direction.moveButton
        encodedLine.segments.forEachIndexed { segmentIdx, segment ->
            val isLastSegment = segmentIdx == encodedLine.segments.size - 1
            if (segment.isBlack) {
                if (segment.length == 1) {
                    addButtonPress(Button.A)
                } else {
                    (0 until (segment.length - 1)).forEach { idx ->
                        if (idx == 0) {
                            _commands.add(Command(duration, Button.A)) // A goes down
                        }
                        _commands.add(Command(duration, Button.A, moveButton)) // A + move
                        if (idx < segment.length - 1) {
                            _commands.add(Command(duration, Button.A))
                        }
                    }
                    _commands.add(Command(duration)) // neutral
                }
            } else {
                repeat(segment.length - 1) {
                    addButtonPress(moveButton)
                }
            }
            if (!isLastSegment) {
                addButtonPress(moveButton) // move to next segment
            }
        }
    }
}

enum class HorizontalDirection(val moveButton: Button) {
    LEFT(Button.LEFT), RIGHT(Button.RIGHT)
}

fun main(args: Array<String>) {
    val parser = ArgParser("java -jar img2splat.jar")
    val filePath by parser.argument(ArgType.String,
        fullName = "input",
        description = "Input image path")
    val durationInput by parser.option(
        ArgType.String,
        fullName = "pressDuration",
        shortName = "d",
        description = "Duration of button presses in seconds, e.g. 0.1"
    )
    val repairInput by parser.option(
        ArgType.String,
        fullName = "repairRows",
        shortName = "r",
        description = "Comma separated-list of image rows or ranges between 0 and ${HEIGHT - 1} inclusive to repair, e.g. 78,99-102,105",
    )

    parser.parse(args)

    val options = try {
        Img2Splat.Options.validateAndBuildFromInput(
            filePath = filePath,
            durationInput = durationInput,
            repairInput = repairInput
        )
    } catch (t: Throwable) {
        println(t.message)
        null
    } ?: return
    Img2Splat(options).splat()
}

class Img2Splat(private val options: Options) {

    data class Result(
        val macroFile: File,
        val previewFile: File
    )
    data class Options(
        val img: BufferedImage,
        val pressDuration: BigDecimal,
        val repairRows: List<Int>,
    ) {
        companion object {
            fun validateAndBuildFromInput(
                filePath: String,
                durationInput: String?,
                repairInput: String?,
            ): Options {
                val imgFile = File(filePath)
                val img: BufferedImage = ImageIO.read(imgFile)
                    ?: throw IllegalArgumentException("$filePath doesn't seem to be a valid image.")

                if (img.width != WIDTH || img.height != HEIGHT) {
                    throw IllegalArgumentException("Image must be $WIDTH x $HEIGHT")
                }

                val pressDuration = durationInput?.let {
                    try {
                        BigDecimal(it)
                    } catch (t: Throwable) {
                        throw IllegalArgumentException("\"$it\" is not a valid button press duration")
                    }
                } ?: DEFAULT_PRESS_DURATION

                if (pressDuration < BigDecimal.ZERO || pressDuration == BigDecimal.ZERO) {
                    throw IllegalArgumentException("Button press duration should be positive and non-zero")
                }

                val repairRanges = repairInput?.split(',')
                    ?.map { token ->
                        try {
                            token.toRowRange()
                        } catch (t: Throwable) {
                            throw IllegalArgumentException("\"$token\" is not a valid repair row or range")
                        }
                    } ?: listOf(0 until HEIGHT) // Default to "repairing" all the rows -- do the whole image
                val repairRows = repairRanges
                    .flatMap { it.toList() }

                return Options(img, pressDuration, repairRows)
            }
        }
    }

    fun splat(): Result {

        val macro = MacroBuilder(options.pressDuration)
        var currentDirection: HorizontalDirection = HorizontalDirection.RIGHT

        macro.addNeutral(MACRO_START_DELAY)
        var currentX = 0
        for (y in (0 until HEIGHT)) {
            // TODO account for next row being a repair row
            if (!options.repairRows.contains(y)) {
                // If we're not supposed to repair this row, just continue down to the next one
                if (y < HEIGHT - 1) { // Don't go off the bottom edge
                    macro.addButtonPress(Button.DOWN)
                }
                continue
            }
            val currentLineExtent = options.img.rowDrawRange(y)
            val nextLineExtent = if (y == HEIGHT - 1) null else { options.img.rowDrawRange(y + 1) }

            // Only add the commands and swap direction if the current line has pixels to draw or the next line has pixels
            // we need to draw, and we should move to the start of the next line
            if (currentLineExtent != null || nextLineExtent != null) {

                val currentLineRange = when (currentDirection) {
                    HorizontalDirection.LEFT -> {
                        val targetX = when {
                            currentLineExtent != null && nextLineExtent != null -> {
                                min(currentLineExtent.first, nextLineExtent.first)
                            }
                            currentLineExtent != null -> {
                                currentLineExtent.first
                            }
                            else -> { // nextLineExtent != null
                                nextLineExtent!!.first // compiler not quite clever enough here
                            }
                        }
                        currentX downTo targetX
                    }
                    HorizontalDirection.RIGHT -> {
                        val targetX = when {
                            currentLineExtent != null && nextLineExtent != null -> {
                                max(currentLineExtent.last, nextLineExtent.last)
                            }
                            currentLineExtent != null -> {
                                currentLineExtent.last
                            }
                            else -> { // nextLineExtent != null
                                nextLineExtent!!.last // compiler not quite clever enough here
                            }
                        }
                        currentX..targetX
                    }
                }
                val encodedLine = runLengthEncode(options.img, y, currentLineRange)
                macro.addRunLengthEncodedLine(encodedLine)
                currentX = currentLineRange.last
                currentDirection = when (currentDirection) {
                    HorizontalDirection.LEFT -> HorizontalDirection.RIGHT
                    HorizontalDirection.RIGHT -> HorizontalDirection.LEFT
                }
            }
            if (y < HEIGHT - 1) { // Don't go off the bottom edge
                macro.addButtonPress(Button.DOWN)
            }
        }
        macro.addButtonPress(Button.MINUS)
        macro.addNeutral(BigDecimal("5.0"))

        val macroFile = File(MACRO_FILENAME)
        val out = macroFile.printWriter()
        out.use {
            macro.commands.forEach { command ->
                out.println(command)
            }
            out.flush()
        }
        val previewFile = generatePreview(macro.commands)
        println("Generated ${macro.commands.size} operations. Woomy!")
        return Result(
            macroFile = macroFile,
            previewFile = previewFile,
        )
    }
}

data class RunLengthSegment(val length: Int, val isBlack: Boolean) {
    init {
        if (length < 1 || length > WIDTH) {
            throw IllegalArgumentException("Illegal segment length $length")
        }
    }
}
data class RunLengthEncodedLine(val segments: List<RunLengthSegment>, val direction: HorizontalDirection)
fun runLengthEncode(
    img: BufferedImage,
    line: Int,
    xProgression: IntProgression,
): RunLengthEncodedLine {
    val segments = mutableListOf<RunLengthSegment>()
    val blackPixels = xProgression.map { x -> img.blackPixel(x, line) }
    val pixelIter = blackPixels.iterator()
    var currentSegment = RunLengthSegment(length = 1, isBlack = pixelIter.next())
    while(pixelIter.hasNext()) {
        val nextBlack = pixelIter.next()
        currentSegment = if (nextBlack == currentSegment.isBlack) {
            currentSegment.copy(
                length = currentSegment.length + 1
            )
        } else {
            // New segment
            segments.add(currentSegment)
            RunLengthSegment(length = 1, isBlack = nextBlack)
        }
    }
    // Add the last segment
    segments.add(currentSegment)
    return RunLengthEncodedLine(
        segments = segments,
        direction = if (xProgression.first < xProgression.last) HorizontalDirection.RIGHT else HorizontalDirection.LEFT,
    )
}

fun BufferedImage.blackPixel(x: Int, y: Int): Boolean {
    val color = getRGB(x, y)
    val r = color shr 16 and 0xFF
    val g = color shr 8 and 0xFF
    val b = color shr 0 and 0xFF
    val luminance = (r * 0.2126f + g * 0.7152f + b * 0.0722f) / 255
    return luminance < 0.5f
}

// returns the x indexes of the outermost drawn pixels, or null if no pixels should be drawn
// TODO: directional?
fun BufferedImage.rowDrawRange(y: Int): IntRange? {
    var start: Int? = null
    var end: Int? = null
    for (x in (0 until WIDTH)) {
        if (blackPixel(x, y)) {
            start = x
            break
        }
    }
    for (x in ((WIDTH - 1) downTo 0)) {
        if (blackPixel(x, y)) {
            end = x
            break
        }
    }
    return if (start == null || end == null) {
        null
    } else {
        start..end
    }
}

fun generatePreview(commands: List<Command>): File {
    val img = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB)
    for (y in (0 until HEIGHT)) {
        for (x in (0 until WIDTH)) {
            img.setRGB(x, y, 0xFF0000FF.toInt())
        }
    }
    var x = 0
    var y = 0
    var aPressed: Boolean
    img.setRGB(0, 0, 0xFFFFFFFF.toInt())
    commands.forEach { command ->
        aPressed = false
        if(command.buttons.contains(Button.A)) {
            img.setRGB(x, y, 0xFF000000.toInt())
            aPressed = true
        }
        if(command.buttons.contains(Button.DOWN)) {
            y += 1
            if (aPressed) {
                img.setRGB(x, y, 0xFF000000.toInt())
            } else {
                img.setRGB(x, y, 0xFFFFFFFF.toInt())
            }
        }
        if(command.buttons.contains(Button.LEFT)) {
            x -= 1
            if (aPressed) {
                img.setRGB(x, y, 0xFF000000.toInt())
            } else {
                img.setRGB(x, y, 0xFFFFFFFF.toInt())
            }
        }
        if(command.buttons.contains(Button.RIGHT)) {
            x += 1
            if (aPressed) {
                img.setRGB(x, y, 0xFF000000.toInt())
            } else {
                img.setRGB(x, y, 0xFFFFFFFF.toInt())
            }
        }
    }
    val outFile = File(PREVIEW_FILENAME)
    ImageIO.write(img, PREVIEW_TYPE, outFile)
    return outFile
}

fun String.toRowRange(): IntRange {
    val range = if (!this.contains("-")) { // Assume it's a single number
            val number = this.toInt()
            number..number
        } else { // Try to split it apart
            val numbers = this.split('-')
            numbers[0].toInt()..numbers[1].toInt()
        }
    if (range.first < 0 || range.first >= HEIGHT -1 ||
        range.last < 0 || range.last >= HEIGHT) {
        throw Exception("Range outside image bounds")
    }
    return range
}
