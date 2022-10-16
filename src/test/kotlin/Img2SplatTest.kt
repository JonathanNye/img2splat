import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals

class Img2SplatTest {

    @Test
    fun `Full preview image matches expected preview`() {
        val testFilePath = this::class.java.getResource("test_jiji.png").file
        val options = Img2Splat.Options.validateAndBuildFromInput(
            filePath = testFilePath,
            durationInput = null,
            repairInput = null,
            cautious = false
        )
        val result = Img2Splat(options).splat()

        val expectedImg = ImageIO.read(this::class.java.getResource("test_jiji_expected.png"))
        val outputImg = ImageIO.read(result.previewFile)

        (0 until 120).forEach { y ->
            (0 until 320).forEach { x ->
                val expectedPx = expectedImg.getRGB(x, y)
                val outputPx = outputImg.getRGB(x, y)
                assertEquals(
                    expectedPx,
                    outputPx,
                    "Pixels not equal at $x,$y: expected=$expectedPx, output=$outputPx"
                )
            }
        }
    }

}