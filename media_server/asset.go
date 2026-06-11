package main

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/url"
	"strings"
	"time"
)

// AssetSigner 为“服务端代理”模式的上游资源生成可校验的代理地址。
//
// 设计目标：
//   - App 拿到的代理地址只能命中服务端**自己签发**的上游 URL + 请求头，
//     避免把 /api/asset 变成任意 URL 的开放代理。
//   - 上游请求头（Referer/Origin/Cookie 等）由服务端在回源时附加，不下发给 App。
//   - 代理地址带过期时间，避免被长期复用。
type AssetSigner struct {
	key  []byte
	aead cipher.AEAD
	ttl  time.Duration
}

type assetPayload struct {
	URL     string            `json:"u"`
	Headers map[string]string `json:"h,omitempty"`
	Exp     int64             `json:"e"`
}

func NewAssetSigner(secret string, ttl time.Duration) *AssetSigner {
	if ttl <= 0 {
		ttl = 6 * time.Hour
	}
	encKey := sha256.Sum256([]byte("media-server-asset-encryption\x00" + secret))
	block, err := aes.NewCipher(encKey[:])
	if err != nil {
		panic(err)
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		panic(err)
	}
	return &AssetSigner{key: []byte(secret), aead: aead, ttl: ttl}
}

// ProxyURL 把上游 rawURL + 上游请求头封装成 base 下的 /asset 代理地址。
func (a *AssetSigner) ProxyURL(base, rawURL string, headers map[string]string) string {
	payload, sig := a.sign(rawURL, headers)
	trimmed := strings.TrimRight(base, "/")
	query := url.Values{}
	query.Set("p", payload)
	query.Set("sig", sig)
	return trimmed + "/asset?" + query.Encode()
}

func (a *AssetSigner) sign(rawURL string, headers map[string]string) (string, string) {
	body, err := json.Marshal(assetPayload{
		URL:     rawURL,
		Headers: headers,
		Exp:     time.Now().Add(a.ttl).Unix(),
	})
	if err != nil {
		// assetPayload 只含可序列化字段，理论上不会失败。
		panic(err)
	}
	nonce := a.nonce(body)
	token := append(nonce, a.aead.Seal(nil, nonce, body, assetTokenAAD)...)
	encoded := base64.RawURLEncoding.EncodeToString(token)
	return encoded, a.signature(encoded)
}

// Verify 校验签名、解密代理 payload 并检查有效期，返回原始上游 URL 与请求头。
func (a *AssetSigner) Verify(payloadB64, sig string) (assetPayload, error) {
	if payloadB64 == "" || sig == "" {
		return assetPayload{}, errors.New("missing asset signature")
	}
	if !hmac.Equal([]byte(sig), []byte(a.signature(payloadB64))) {
		return assetPayload{}, errors.New("invalid asset signature")
	}
	token, err := base64.RawURLEncoding.DecodeString(payloadB64)
	if err != nil {
		return assetPayload{}, err
	}
	nonceSize := a.aead.NonceSize()
	if len(token) <= nonceSize {
		return assetPayload{}, errors.New("invalid asset payload")
	}
	nonce := token[:nonceSize]
	ciphertext := token[nonceSize:]
	body, err := a.aead.Open(nil, nonce, ciphertext, assetTokenAAD)
	if err != nil {
		return assetPayload{}, err
	}
	var payload assetPayload
	if err := json.Unmarshal(body, &payload); err != nil {
		return assetPayload{}, err
	}
	if strings.TrimSpace(payload.URL) == "" {
		return assetPayload{}, errors.New("empty asset url")
	}
	if payload.Exp > 0 && time.Now().Unix() > payload.Exp {
		return assetPayload{}, errAssetExpired
	}
	return payload, nil
}

func (a *AssetSigner) signature(body string) string {
	mac := hmac.New(sha256.New, a.key)
	_, _ = mac.Write([]byte(body))
	return hex.EncodeToString(mac.Sum(nil))
}

func (a *AssetSigner) nonce(body []byte) []byte {
	mac := hmac.New(sha256.New, a.key)
	_, _ = mac.Write([]byte("media-server-asset-nonce\x00"))
	_, _ = mac.Write(body)
	sum := mac.Sum(nil)
	return sum[:a.aead.NonceSize()]
}

var errAssetExpired = errors.New("asset url expired")

var assetTokenAAD = []byte("media-server-asset-v1")
