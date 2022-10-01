import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals

class Img2SplatTest {

    @Test
    fun `Full preview image matches monochrome input image`() {
        val testFilePath = this::class.java.getResource("test_jiji.png").file
        val options = Img2Splat.Options.validateAndBuildFromInput(
            filePath = testFilePath,
            durationInput = null,
            repairInput = null,
        )
        val result = Img2Splat(options).splat()

        val inputImg = ImageIO.read(File(testFilePath))
        val outputImg = ImageIO.read(result.previewFile)

        (0 until 120).forEach { y ->
            (0 until 320).forEach { x ->
                val inputPx = inputImg.getRGB(x, y)
                val outputPx = outputImg.getRGB(x, y)
                assertEquals(
                    inputPx,
                    outputPx,
                    "Pixels not equal at $x,$y: input=$inputPx, output=$outputPx"
                )
            }
        }
    }

}