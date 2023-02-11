import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import java.awt.image.BufferedImage
import java.io.File
import java.math.BigDecimal
import java.text.DecimalFormat
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

const val WIDTH = 320
const val HEIGHT = 120
const val MACRO_FILENAME = "splat_macro.txt"
const val PREVIEW_FILENAME = "macro_preview.png"
const val MACRO_FILENAME_INVERTED = "splat_macro_inverted.txt"
const val PREVIEW_FILENAME_INVERTED = "macro_preview_inverted.png"
const val PREVIEW_TYPE = "png"
val DEFAULT_PRESS_DURATION = BigDecimal("0.1")
val MACRO_START_DELAY = BigDecimal("3.0")
val MACRO_END_DELAY = BigDecimal("5.0")
const val CAUTIOUS_EXTRA_SHUNT_MOVES = 3

enum class Button(val macroToken: String) {
    DOWN("DPAD_DOWN"),
    LEFT("DPAD_LEFT"),
    RIGHT("DPAD_RIGHT"),
    A("A"),
    B("B"),
    HOME("HOME"),
    MINUS("MINUS"),
}

class Command(
    private val duration: BigDecimal,
    vararg val buttons: Button, // We can have zero, one, or many buttons pressed
    // If everything is going well, this command *shouldn't* do anything and can thus be ignored for preview generation
    // purposes, but may serve to help reduce error propagation. An example is trying to move the cursor over the edge
    // of the drawing canvas.
    intendedIdempotent: Boolean = false,
) {
    val intendedIdempotent = intendedIdempotent || buttons.isEmpty()
    override fun toString() = if (buttons.isEmpty()) {
        "${duration}s"
    } else {
        "${buttons.joinToString(separator = " ") { it.macroToken }} ${duration}s"
    }
}

class MacroBuilder(
    private val defaultPressDuration: BigDecimal,
    private val inverted: Boolean,
) {
    private val _commands: MutableList<Command> = mutableListOf()
    val commands: List<Command> = _commands
    private val drawButton = if (inverted) Button.B else Button.A

    fun addButtonPress(vararg buttons: Button) {
        _commands.add(Command(defaultPressDuration, *buttons))
        addNeutral(defaultPressDuration)
    }

    // For button presses that *shouldn't* change the state of the drawing canvas/cursor if everything is going well,
    // but may help to fix things if they do result in changes
    fun addIdempotentButtonPress(vararg buttons: Button) {
        _commands.add(Command(defaultPressDuration, *buttons, intendedIdempotent = true))
    }

    fun addNeutral(duration: BigDecimal = defaultPressDuration) {
        _commands.add(Command(duration))
    }

    fun drawImage(img: BufferedImage, repairRows: List<Int>, cautious: Boolean) {
        var currentDirection: HorizontalDirection = HorizontalDirection.RIGHT
        var currentX = 0

        for (y in (0 until HEIGHT)) {
            val currentLineExtent = if (repairRows.contains(y)) {
                img.rowDrawRange(y, inverted, cautious)
            } else null
            val nextLineExtent = if (repairRows.contains(y + 1)) {
                img.rowDrawRange(y + 1, inverted, cautious)
            } else null

            // Only add the commands and swap direction if:
            // 1. the current line has pixels to draw
            // 2. the next line has pixels we need to draw, and we should move to the start of the next line
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
                val encodedLine = runLengthEncode(img, y, currentLineRange)
                addRunLengthEncodedLine(encodedLine)

                // If we're in cautious mode, we can also add a few extra moves to force the cursor into the edge of
                // the canvas in case we came up short and help reduce error propagation.
                if (cautious) {
                    repeat(CAUTIOUS_EXTRA_SHUNT_MOVES) {
                        addIdempotentButtonPress(currentDirection.moveButton)
                    }
                }

                currentX = currentLineRange.last
                currentDirection = when (currentDirection) {
                    HorizontalDirection.LEFT -> HorizontalDirection.RIGHT
                    HorizontalDirection.RIGHT -> HorizontalDirection.LEFT
                }
            }
            if (y < HEIGHT - 1) { // Don't go off the bottom edge
                addButtonPress(Button.DOWN)
            }
        }
    }

    private fun addRunLengthEncodedLine(
        encodedLine: RunLengthEncodedLine,
        duration: BigDecimal = defaultPressDuration
    ) {
        val moveButton = encodedLine.direction.moveButton
        encodedLine.segments.forEachIndexed { segmentIdx, segment ->
            val isLastSegment = segmentIdx == encodedLine.segments.size - 1
            val shouldDraw = (segment.isBlack && !inverted) || (!segment.isBlack && inverted)
            if (shouldDraw) {
                if (segment.length == 1) {
                    addButtonPress(drawButton)
                } else {
                    (0 until (segment.length - 1)).forEach { idx ->
                        if (idx == 0) {
                            _commands.add(Command(duration, drawButton)) // draw button goes down
                        }
                        _commands.add(Command(duration, drawButton, moveButton)) // draw button + move
                        if (idx < segment.length - 1) {
                            _commands.add(Command(duration, drawButton)) // draw button held
                        }
                    }
                    _commands.add(Command(duration)) // neutral, draw button goes up
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

    fun writeTo(file: File) {
        val out = file.printWriter()
        out.use {
            commands.forEach { command ->
                out.println(command)
            }
            out.flush()
        }
    }
}

enum class HorizontalDirection(val moveButton: Button) {
    LEFT(Button.LEFT), RIGHT(Button.RIGHT)
}

fun main(args: Array<String>) {
    val parser = ArgParser("java -jar img2splat.jar")
    val filePath by parser.argument(
        ArgType.String,
        fullName = "input",
        description = "Input image path"
    )
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
    val cautious by parser.option(
        ArgType.Boolean,
        fullName = "cautious",
        shortName = "c",
        description = "Flag to turn off line draw extent optimization and force drawing edge-to-edge, which can help reduce error propagation"
    ).default(false)

    parser.parse(args)

    val options = try {
        Img2Splat.Options.validateAndBuildFromInput(
            filePath = filePath,
            durationInput = durationInput,
            repairInput = repairInput,
            cautious = cautious,
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
        val previewFile: File,
        val invertedMacroFile: File,
        val invertedPreviewFile: File,
    )

    data class Options(
        val img: BufferedImage,
        val pressDuration: BigDecimal,
        val repairRows: List<Int>,
        val cautious: Boolean,
    ) {
        companion object {
            fun validateAndBuildFromInput(
                filePath: String,
                durationInput: String?,
                repairInput: String?,
                cautious: Boolean,
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

                return Options(img, pressDuration, repairRows, cautious)
            }
        }
    }

    fun splat(): Result {

        val regularMacro = MacroBuilder(options.pressDuration, inverted = false)
        val invertedMacro = MacroBuilder(options.pressDuration, inverted = true)

        for (macro in arrayOf(regularMacro, invertedMacro)) {
            macro.addNeutral(MACRO_START_DELAY)
            macro.drawImage(options.img, options.repairRows, options.cautious)
            macro.addButtonPress(Button.MINUS)
            macro.addNeutral(MACRO_END_DELAY)
        }

        val regularFile = File(MACRO_FILENAME)
        regularMacro.writeTo(regularFile)
        val invertedFile = File(MACRO_FILENAME_INVERTED)
        invertedMacro.writeTo(invertedFile)

        val regularPreviewFile = File(PREVIEW_FILENAME)
        generatePreview(regularPreviewFile, regularMacro.commands, inverted = false)
        val invertedPreviewFile = File(PREVIEW_FILENAME_INVERTED)
        generatePreview(invertedPreviewFile, invertedMacro.commands, inverted = true)

        val regNumCommands = regularMacro.commands.size
        val invNumCommands = invertedMacro.commands.size
        val invRecommended = invNumCommands < regNumCommands
        val recommendedFile = if (invRecommended) {
            MACRO_FILENAME_INVERTED
        } else {
            MACRO_FILENAME
        }

        println("Generated $MACRO_FILENAME with $regNumCommands operations.")
        println("Generated $MACRO_FILENAME_INVERTED with $invNumCommands operations.")

        val higher = max(regNumCommands, invNumCommands).toDouble()
        val lower = min(regNumCommands, invNumCommands).toDouble()
        val percentDecrease = abs(higher - lower) / higher * 100.0
        if (percentDecrease < 1) {
            println("They're pretty close! Probably just use $MACRO_FILENAME if you're doing a first run.")
        } else {
            val formattedDiff = DecimalFormat("0.0#").format(percentDecrease)
            println("Recommend using $recommendedFile, which has $formattedDiff% fewer operations.")
        }
        println("If you're repairing after an initial run, stick to the same type you started with.")
        println("If you decide to use the inverted macro, don't forget to fill your canvas with black pixels before starting!")
        println("Woomy!")

        return Result(
            macroFile = regularFile,
            previewFile = regularPreviewFile,
            invertedMacroFile = invertedFile,
            invertedPreviewFile = invertedPreviewFile,
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
    val pixelIter = xProgression.asSequence().map { x -> img.blackPixel(x, line) }.iterator()
    var currentSegment = RunLengthSegment(length = 1, isBlack = pixelIter.next())
    while (pixelIter.hasNext()) {
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

// Returns the x indexes of the outermost pixels to draw, or null if no pixels should be drawn
fun BufferedImage.rowDrawRange(y: Int, inverted: Boolean, cautious: Boolean): IntRange? {
    var start: Int? = null
    var end: Int? = null
    for (x in (0 until WIDTH)) {
        val blackPx = blackPixel(x, y)
        if ((blackPx && !inverted) || (!blackPx && inverted)) {
            start = x
            break
        }
    }
    for (x in ((WIDTH - 1) downTo 0)) {
        val blackPx = blackPixel(x, y)
        if ((blackPx && !inverted) || (!blackPx && inverted)) {
            end = x
            break
        }
    }
    return if (start == null || end == null) {
        null
    } else {
        if (cautious) {
            0 until WIDTH
        } else {
            start..end
        }
    }
}

const val BLUE = 0xFF0000FF.toInt()
const val WHITE = 0xFFFFFFFF.toInt()
const val BLACK = 0xFF000000.toInt()
fun generatePreview(file: File, commands: List<Command>, inverted: Boolean) {
    val img = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB)
    for (y in (0 until HEIGHT)) {
        for (x in (0 until WIDTH)) {
            img.setRGB(x, y, BLUE)
        }
    }
    val visitedColor = if (inverted) BLACK else WHITE
    var x = 0
    var y = 0
    img.setRGB(0, 0, visitedColor)
    for (command in commands) {
        if (command.intendedIdempotent) {
            continue
        }
        var bPressed = false
        var aPressed = false
        if (command.buttons.contains(Button.A)) {
            img.setRGB(x, y, BLACK)
            aPressed = true
        }
        if (command.buttons.contains(Button.B)) {
            img.setRGB(x, y, WHITE)
            bPressed = true
        }
        if (command.buttons.contains(Button.DOWN)) {
            y += 1
        }
        if (command.buttons.contains(Button.LEFT)) {
            x -= 1
        }
        if (command.buttons.contains(Button.RIGHT)) {
            x += 1
        }
        val moved = command.buttons.any {
            it == Button.DOWN || it == Button.LEFT || it == Button.RIGHT
        }
        if (moved) {
            if (aPressed) {
                img.setRGB(x, y, BLACK)
            }
            if (bPressed) {
                img.setRGB(x, y, WHITE)
            }
            if (!aPressed && !bPressed) {
                img.setRGB(x, y, visitedColor)
            }
        }
    }
    ImageIO.write(img, PREVIEW_TYPE, file)
}

fun String.toRowRange(): IntRange {
    val range = if (!this.contains("-")) { // Assume it's a single number
        val number = this.toInt()
        number..number
    } else { // Try to split it apart
        val numbers = this.split('-')
        numbers[0].toInt()..numbers[1].toInt()
    }
    if (range.first < 0 || range.first >= HEIGHT ||
        range.last < 0 || range.last >= HEIGHT
    ) {
        throw Exception("Range outside image bounds")
    }
    return range
}
