import java.awt.image.BufferedImage
import kotlin.test.assertEquals

fun assertImagesAreIdentical(
    expected: BufferedImage,
    actual: BufferedImage,
) {
    if (expected.width != actual.width || expected.height != actual.height) {
        throw AssertionError("Images are not same dimensions." +
                "expected=${expected.width}x${expected.height}, " +
                "actual=${actual.width}x${actual.height}")
    }

    (0 until expected.height).forEach { y ->
        (0 until expected.width).forEach { x ->
            val expectedPx = expected.getRGB(x, y)
            val actualPx = actual.getRGB(x, y)
            assertEquals(
                expectedPx.toUInt().toString(16),
                actualPx.toUInt().toString(16),
                "Pixels not equal at $x,$y"
            )
        }
    }
}
