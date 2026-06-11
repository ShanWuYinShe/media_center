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
	ID       string `json:"id,omitempty"`
	BVID     string `json:"bvid"`
}

type signedPlaybackID struct {
	Version  int    `json:"v"`
	Type     string `json:"t"`
	Provider string `json:"p"`
	ID       string `json:"id,omitempty"`
	Variant  string `json:"variant,omitempty"`
	BVID     string `json:"bvid"`
	Quality  int    `json:"q"`
}

type ProviderVideoRef struct {
	Provider string
	ID       string
}

func NewIDCodec(secret string) *IDCodec {
	return &IDCodec{key: []byte(secret)}
}

func (c *IDCodec) EncodeVideoID(bvid string) string {
	return c.EncodeProviderVideoID("bilibili", bvid)
}

func (c *IDCodec) EncodeProviderVideoID(provider, id string) string {
	payload := signedVideoID{
		Version:  1,
		Type:     "video",
		Provider: provider,
		ID:       id,
	}
	if provider == "bilibili" {
		payload.BVID = id
	}
	return c.mustEncode(signedVideoID{
		Version:  payload.Version,
		Type:     payload.Type,
		Provider: payload.Provider,
		ID:       payload.ID,
		BVID:     payload.BVID,
	})
}

func (c *IDCodec) DecodeVideoID(value string) (string, error) {
	ref, err := c.DecodeVideoRef(value)
	if err != nil {
		return "", err
	}
	if ref.Provider != "bilibili" {
		return "", errors.New("invalid video provider")
	}
	return ref.ID, nil
}

func (c *IDCodec) DecodeVideoRef(value string) (ProviderVideoRef, error) {
	trimmed := strings.TrimSpace(value)
	if strings.HasPrefix(strings.ToUpper(trimmed), "BV") {
		return ProviderVideoRef{Provider: "bilibili", ID: trimmed}, nil
	}
	var payload signedVideoID
	if err := c.decode(trimmed, &payload); err != nil {
		return ProviderVideoRef{}, err
	}
	if payload.Type != "video" || payload.Provider == "" {
		return ProviderVideoRef{}, errors.New("invalid video id")
	}
	id := payload.ID
	if id == "" {
		id = payload.BVID
	}
	if id == "" {
		return ProviderVideoRef{}, errors.New("invalid video id")
	}
	return ProviderVideoRef{Provider: payload.Provider, ID: id}, nil
}

func (c *IDCodec) EncodePlaybackID(bvid string, quality int) string {
	return c.EncodeProviderPlaybackID("bilibili", bvid, qualityLabel(quality), quality)
}

func (c *IDCodec) EncodeProviderPlaybackID(provider, id, variant string, quality int) string {
	payload := signedPlaybackID{
		Version:  1,
		Type:     "playback",
		Provider: provider,
		ID:       id,
		Variant:  variant,
		Quality:  quality,
	}
	if provider == "bilibili" {
		payload.BVID = id
	}
	return c.mustEncode(signedPlaybackID{
		Version:  payload.Version,
		Type:     payload.Type,
		Provider: payload.Provider,
		ID:       payload.ID,
		Variant:  payload.Variant,
		BVID:     payload.BVID,
		Quality:  payload.Quality,
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
