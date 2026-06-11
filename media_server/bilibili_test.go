package main

import "testing"

func TestWBIKeysOrNavErrorAllowsAnonymousNavResponse(t *testing.T) {
	var payload bilibiliNavResponse
	payload.Code = -101
	payload.Data.WBIImg.ImgURL = "https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png"
	payload.Data.WBIImg.SubURL = "https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png"

	imgKey, subKey, err := wbiKeysOrNavError(payload)
	if err != nil {
		t.Fatalf("anonymous nav response with WBI keys should be accepted: %v", err)
	}
	if imgKey != "7cd084941338484aae1ad9425b84077c" {
		t.Fatalf("unexpected img key: %s", imgKey)
	}
	if subKey != "4932caff0ff746eab6f01bf08b70ac45" {
		t.Fatalf("unexpected sub key: %s", subKey)
	}
}

func TestWBIKeysOrNavErrorRejectsNonZeroCodeWithoutKeys(t *testing.T) {
	var payload bilibiliNavResponse
	payload.Code = -101

	_, _, err := wbiKeysOrNavError(payload)
	if err == nil {
		t.Fatal("expected nav response without keys to fail")
	}
}
