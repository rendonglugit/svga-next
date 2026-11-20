// SPDX-License-Identifier: Apache-2.0
package com.opensource.svgaplayer;

import android.content.Context;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class SvgaCompatJavaTest {

    @Test
    public void testJavaStaticAccessOnSoundManagerAndCache() {
        Context context = RuntimeEnvironment.getApplication();

        // 1. Static calls on SVGASoundManager without .INSTANCE
        SVGASoundManager.init();
        Assert.assertTrue(SVGASoundManager.isInit());
        SVGASoundManager.setVolume(0.5f);

        File dummyDir = new File(context.getCacheDir(), "test_java");
        dummyDir.mkdirs();
        org.json.JSONObject dummyJson = new org.json.JSONObject();
        SVGAVideoEntity entity = new SVGAVideoEntity(dummyJson, dummyDir);

        SVGASoundManager.setVolume(0f, entity);
        entity.clearAudioList();
        entity.clearCallback();

        // 2. Static calls on SVGAActivitiesCache without .INSTANCE
        Assert.assertNotNull(SVGAActivitiesCache.getInstance());
        Assert.assertNotNull(SVGAActivitiesCache.Companion.getInstance());
        SVGAActivitiesCache.getInstance().setMaxSize(5 * 1024 * 1024);

        // 3. SVGAImageView Java API calls
        SVGAImageView imageView = new SVGAImageView(context);
        imageView.startAnimationFromAppointFrame(0);
        imageView.clear();

        SVGASoundManager.release();
    }
}
