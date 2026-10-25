package com.teddy.streamtotv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TokenRewriterTest {
    private static final String BASE = "https://cdn.example.st/ch29/tracks-v1a1/";

    @Test
    public void segmentGetsTheRenewedToken() {
        TokenRewriter r = new TokenRewriter();
        r.reset(BASE + "mono.m3u8?token=OLD");
        assertTrue(r.update(BASE + "mono.m3u8?token=NEW"));
        assertEquals(BASE + "2026/10/25/17/56/14-04000.ts?token=NEW",
                r.rewrite(BASE + "2026/10/25/17/56/14-04000.ts?token=OLD"));
        assertEquals(BASE + "mono.m3u8?token=NEW", r.rewrite(BASE + "mono.m3u8?token=OLD"));
        assertEquals(1, r.generation());
    }

    @Test
    public void onlyParametersTheRequestCarriesChange() {
        TokenRewriter r = new TokenRewriter();
        r.reset(BASE + "index.m3u8?e=1&st=a");
        r.update(BASE + "index.m3u8?e=2&st=b&extra=x");
        assertEquals(BASE + "seg.ts?st=b&q=720", r.rewrite(BASE + "seg.ts?st=a&q=720"));
        assertEquals(BASE + "seg.ts", r.rewrite(BASE + "seg.ts"));
    }

    @Test
    public void sharedParametersAreLeftAloneUntilARenewalChangesThem() {
        TokenRewriter r = new TokenRewriter();
        r.reset(BASE + "index.m3u8?token=T&q=auto");
        String segment = BASE + "seg_5.ts?token=T&q=720";
        assertEquals(segment, r.rewrite(segment));
        assertTrue(r.update(BASE + "index.m3u8?token=U&q=auto"));
        assertEquals(BASE + "seg_5.ts?token=U&q=720", r.rewrite(segment));
    }

    @Test
    public void anotherPlaylistsNewParametersAreNotARenewal() {
        TokenRewriter r = new TokenRewriter();
        r.reset(BASE + "master.m3u8?token=A");
        assertFalse(r.update(BASE + "chunklist_720.m3u8?token=A&sid=9"));
        assertTrue(r.update(BASE + "chunklist_720.m3u8?token=B&sid=9"));
        assertFalse(r.update(BASE + "master.m3u8?token=B"));
        assertEquals(1, r.generation());
        assertEquals(BASE + "a.ts?token=B&sid=9", r.rewrite(BASE + "a.ts?token=A&sid=9"));
    }

    @Test
    public void otherHostsAreLeftAlone() {
        TokenRewriter r = new TokenRewriter();
        r.reset(BASE + "mono.m3u8?token=OLD");
        r.update(BASE + "mono.m3u8?token=NEW");
        String other = "https://ads.example.com/x.ts?token=OLD";
        assertEquals(other, r.rewrite(other));
    }

    @Test
    public void updateFromAnotherHostOrWithoutQueryIsIgnored() {
        TokenRewriter r = new TokenRewriter();
        r.reset(BASE + "mono.m3u8?token=OLD");
        assertFalse(r.update("https://elsewhere.st/mono.m3u8?token=NEW"));
        assertFalse(r.update(BASE + "mono.m3u8"));
        assertFalse(r.update(BASE + "mono.m3u8?token=OLD"));
        assertEquals(0, r.generation());
    }

    @Test
    public void hostIgnoresCaseAndUserInfoAndKeepsPort() {
        assertEquals("cdn.example.st:8443", TokenRewriter.hostOf("https://u:p@CDN.Example.st:8443/a?b=c"));
        assertEquals(null, TokenRewriter.hostOf("not a url"));
    }

    @Test
    public void fragmentSurvives() {
        TokenRewriter r = new TokenRewriter();
        r.reset(BASE + "a.m3u8?t=1");
        r.update(BASE + "a.m3u8?t=2");
        assertEquals(BASE + "a.ts?t=2#x", r.rewrite(BASE + "a.ts?t=1#x"));
    }
}
