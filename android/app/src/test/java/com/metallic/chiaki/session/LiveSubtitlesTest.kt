// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveSubtitlesTest
{
	@Test
	fun downmixStereo()
	{
		val src = shortArrayOf(100, 300, -200, -400, Short.MAX_VALUE, Short.MAX_VALUE)
		val dst = ShortArray(src.size)
		assertEquals(3, LiveSubtitles.downmix(src, src.size, 2, dst))
		assertArrayEquals(shortArrayOf(200, -300, Short.MAX_VALUE), dst.copyOf(3))
	}

	@Test
	fun downmixMono()
	{
		val src = shortArrayOf(1, 2, 3)
		val dst = ShortArray(3)
		assertEquals(3, LiveSubtitles.downmix(src, 3, 1, dst))
		assertArrayEquals(src, dst)
	}
}
