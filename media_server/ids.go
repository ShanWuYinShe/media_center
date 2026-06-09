package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"strings"
)

type IDCodec struct {
	key []byte
}

type signedVideoID struct {
	Version  int    `json:"v"`
	Type     string `json:"t"`
	Provider string `json:"p"`
	BVID     string `json:"bvid"`
}

type signedPlaybackID struct {
	Version  int    `json:"v"`
	Type     string `json:"t"`
	Provider string `json:"p"`
	BVID     string `json:"bvid"`
	Quality  int    `json:"q"`
}

func NewIDCodec(secret string) *IDCodec {
	return &IDCodec{key: []byte(secret)}
}

func (c *IDCodec) EncodeVideoID(bvid string) string {
	return c.mustEncode(signedVideoID{
		Version:  1,
		Type:     "video",
		Provider: "bilibili",
		BVID:     bvid,
	})
}

func (c *IDCodec) DecodeVideoID(value string) (string, error) {
	trimmed := strings.TrimSpace(value)
	if strings.HasPrefix(strings.ToUpper(trimmed), "BV") {
		return trimmed, nil
	}
	var payload signedVideoID
	if err := c.decode(trimmed, &payload); err != nil {
		return "", err
	}
	if payload.Type != "video" || payload.Provider != "bilibili" || payload.BVID == "" {
		return "", errors.New("invalid video id")
	}
	return payload.BVID, nil
}

func (c *IDCodec) EncodePlaybackID(bvid string, quality int) string {
	return c.mustEncode(signedPlaybackID{
		Version:  1,
		Type:     "playback",
		Provider: "bilibili",
		BVID:     bvid,
		Quality:  quality,
	})
}

func (c *IDCodec) mustEncode(payload any) string {
	encoded, err := c.encode(payload)
	if err != nil {
		panic(err)
	}
	return encoded
}

func (c *IDCodec) encode(payload any) (string, error) {
	body, err := json.Marshal(payload)
	if err != nil {
		return "", err
	}
	bodyEncoded := base64.RawURLEncoding.EncodeToString(body)
	signature := c.signature(bodyEncoded)
	return "v1." + bodyEncoded + "." + signature, nil
}

func (c *IDCodec) decode(value string, out any) error {
	parts := strings.Split(value, ".")
	if len(parts) != 3 || parts[0] != "v1" {
		return errors.New("invalid id format")
	}
	if !hmac.Equal([]byte(parts[2]), []byte(c.signature(parts[1]))) {
		return errors.New("invalid id signature")
	}
	body, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return err
	}
	return json.Unmarshal(body, out)
}

func (c *IDCodec) signature(body string) string {
	mac := hmac.New(sha256.New, c.key)
	_, _ = mac.Write([]byte(body))
	return hex.EncodeToString(mac.Sum(nil))
}
