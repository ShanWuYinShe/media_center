package main

import "testing"

func TestIDCodecRoundTrip(t *testing.T) {
	codec := NewIDCodec("test-secret")
	encoded := codec.EncodeVideoID("BV1xx411c7mD")
	decoded, err := codec.DecodeVideoID(encoded)
	if err != nil {
		t.Fatalf("decode encoded id: %v", err)
	}
	if decoded != "BV1xx411c7mD" {
		t.Fatalf("unexpected decoded id: %s", decoded)
	}
}

func TestDecodeVideoIDAllowsRawBVID(t *testing.T) {
	codec := NewIDCodec("test-secret")
	decoded, err := codec.DecodeVideoID("BV1xx411c7mD")
	if err != nil {
		t.Fatalf("decode raw bvid: %v", err)
	}
	if decoded != "BV1xx411c7mD" {
		t.Fatalf("unexpected decoded raw bvid: %s", decoded)
	}
}
