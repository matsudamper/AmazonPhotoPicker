package net.matsudamper.amazonphotopicker

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageUrlResolverTest {
    @Test
    fun thumbnailUrlProducesOriginalAndLargeCandidates() {
        val url = "https://thumbnails-photos.amazon.co.jp/v1/thumbnail/abcDEF123?viewBox=366%2C366&ownerId=OWNER1"
        val result = ImageUrlResolver.candidates(url, "https://www.amazon.co.jp/photos/all")
        assertEquals(
            listOf(
                "https://www.amazon.co.jp/drive/v1/nodes/abcDEF123/contentRedirection?querySuffix=%3Fdownload%3Dtrue&ownerId=OWNER1",
                "https://thumbnails-photos.amazon.co.jp/v1/thumbnail/abcDEF123?viewBox=4096%2C4096&ownerId=OWNER1",
                url,
            ),
            result,
        )
    }

    @Test
    fun amazonHostIsInferredFromThumbnailHost() {
        val url = "https://thumbnails-photos.amazon.co.jp/v1/thumbnail/node1?viewBox=100%2C100"
        val result = ImageUrlResolver.candidates(url, null)
        assertEquals(
            "https://www.amazon.co.jp/drive/v1/nodes/node1/contentRedirection?querySuffix=%3Fdownload%3Dtrue",
            result.first(),
        )
    }

    @Test
    fun otherUrlIsReturnedAsIs() {
        val url = "https://example.com/a.jpg"
        assertEquals(listOf(url), ImageUrlResolver.candidates(url, "https://www.amazon.co.jp/photos/"))
    }
}
