import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import javax.imageio.ImageIO

class Img2SplatTest {

    companion object {
        val JIJI_TEST_INPUT = Img2SplatTest::class.java.getResource("test_jiji.png").file
        val SQUARES_TEST_INPUT = Img2SplatTest::class.java.getResource("test_squares.png").file
    }

    @Test
    fun `Full preview image matches expected preview`() {
        val options = Img2Splat.Options.validateAndBuildFromInput(
            filePath = JIJI_TEST_INPUT,
            durationInput = null,
            partialInput = null,
            repairInput = null,
            cautious = false
        )
        val result = Img2Splat(options).splat()

        val expectedImg = ImageIO.read(this::class.java.getResource("jiji_full_uncautious_expected.png"))
        val outputImg = ImageIO.read(result.previewFile)

        assertImagesAreIdentical(
            expected = expectedImg,
            actual = outputImg,
        )
    }

    @Test
    fun `Partial and repair options cannot be provided together`() {
        assertThrows<Throwable> {
            Img2Splat.Options.validateAndBuildFromInput(
                filePath = "don't care",
                durationInput = null,
                partialInput = "0",
                repairInput = "0",
                cautious = false
            )
        }
    }

    @Test
    fun `Repair preview image matches expected preview`() {
        val options = Img2Splat.Options.validateAndBuildFromInput(
            filePath = SQUARES_TEST_INPUT,
            durationInput = null,
            partialInput = null,
            repairInput = "0,5,24,64,96,115,119",
            cautious = false
        )

        val result = Img2Splat(options).splat()

        val expectedImg = ImageIO.read(this::class.java.getResource("squares_repair_expected.png"))
        val outputImg = ImageIO.read(result.previewFile)

        assertImagesAreIdentical(
            expected = expectedImg,
            actual = outputImg,
        )
    }

    @Test
    fun `Partial preview image matches expected preview`() {
        val options = Img2Splat.Options.validateAndBuildFromInput(
            filePath = SQUARES_TEST_INPUT,
            durationInput = null,
            partialInput = "8-12,60,96,119",
            repairInput = null,
            cautious = false
        )

        val result = Img2Splat(options).splat()

        val expectedImg = ImageIO.read(this::class.java.getResource("squares_partial_expected.png"))
        val outputImg = ImageIO.read(result.previewFile)

        assertImagesAreIdentical(
            expected = expectedImg,
            actual = outputImg,
        )
    }

    @Test
    fun `Ensure last line can be repaired`() {
        val options = Img2Splat.Options.validateAndBuildFromInput(
            filePath = JIJI_TEST_INPUT,
            durationInput = null,
            partialInput = null,
            repairInput = "119",
            cautious = false
        )

        assertDoesNotThrow { Img2Splat(options).splat() }
    }
}