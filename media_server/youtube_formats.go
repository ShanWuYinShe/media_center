package main

import (
	"sort"
	"strconv"
	"strings"
)

type ytdlpFormat struct {
	FormatID string `json:"format_id"`
	URL      string `json:"url"`
	Ext      string `json:"ext"`
	Height   int    `json:"height"`
	// FPS 用 float64:yt-dlp 对部分格式(如 storyboard)会给出小数帧率,
	// 用 int 会导致整个 JSON 解码失败(Go json: cannot unmarshal number).
	FPS        float64 `json:"fps"`
	VCodec     string  `json:"vcodec"`
	ACodec     string  `json:"acodec"`
	Protocol   string  `json:"protocol"`
	FormatNote string  `json:"format_note"`
	// Abr/Tbr 用于在纯音频格式中挑最高码率的一条;YouTube 的 m4a 音轨
	// 通常带 abr,tbr 作为兜底。
	Abr float64 `json:"abr"`
	Tbr float64 `json:"tbr"`
}

type ytdlpInfo struct {
	ID        string        `json:"id"`
	Title     string        `json:"title"`
	Duration  float64       `json:"duration"`
	Thumbnail string        `json:"thumbnail"`
	Formats   []ytdlpFormat `json:"formats"`
}

// PlaybackOptionResponse 与 App 端 PlaybackOption(kotlinx.serialization)字段一一对应。
type PlaybackOptionResponse struct {
	ID       string `json:"id"`
	Kind     string `json:"kind"`
	Label    string `json:"label"`
	URL      string `json:"url"`
	AudioURL string `json:"audioUrl,omitempty"`
	// AudioMimeType 只在音视频分离时下发:音频直链常无扩展名,显式声明
	// 可让 ExoPlayer 直接选中正确的提取器/容器解析器。
	AudioMimeType string            `json:"audioMimeType,omitempty"`
	MimeType      string            `json:"mimeType,omitempty"`
	Headers       map[string]string `json:"headers"`
	QN            int               `json:"qn"`
}

// QualityOptionResponse 与 App 端 QualityOption 对应(qn 直接用视频高度,如 1080)。
type QualityOptionResponse struct {
	QN    int    `json:"qn"`
	Label string `json:"label"`
}

// PlaybackResultResponse 与 App 端 PlaybackResult 对应;YouTube 单视频无多P概念,
// pages 省略(App 端默认单页)。
type PlaybackResultResponse struct {
	Options            []PlaybackOptionResponse `json:"options"`
	AvailableQualities []QualityOptionResponse  `json:"availableQualities"`
	DurationSeconds    int64                    `json:"durationSeconds,omitempty"`
}

// isHLS 判断是否 HLS 播放列表(yt-dlp protocol 为 m3u8/m3u8_native)。
func isHLS(f ytdlpFormat) bool {
	return strings.Contains(f.Protocol, "m3u8")
}

func hasVideoTrack(f ytdlpFormat) bool { return f.VCodec != "" && f.VCodec != "none" }

func hasAudioTrack(f ytdlpFormat) bool { return f.ACodec != "" && f.ACodec != "none" }

type playbackCandidate struct {
	format ytdlpFormat
	audio  *ytdlpFormat
	kind   int
}

// codecRank 给电视盒子硬解能力排序(H.264 > VP9 > AV1)。同清晰度下优先
// H.264:老 TV 盒子对 VP9/AV1 硬解支持参差,软解 4K 会直接卡死。
func codecRank(codec string) int {
	switch {
	case strings.HasPrefix(codec, "avc1"), strings.HasPrefix(codec, "h264"):
		return 0
	case strings.HasPrefix(codec, "vp9"), strings.HasPrefix(codec, "vp09"):
		return 1
	case strings.HasPrefix(codec, "av01"), strings.HasPrefix(codec, "av1"):
		return 2
	default:
		return 3
	}
}

// bestAudioFormat 在纯音频轨中挑一条:优先 m4a/mp4a(ExoPlayer 兼容性最好),
// 再比码率。返回 nil 表示没有可用音轨。
func bestAudioFormat(audio []ytdlpFormat) *ytdlpFormat {
	if len(audio) == 0 {
		return nil
	}
	sort.SliceStable(audio, func(i, j int) bool {
		ai, aj := audio[i], audio[j]
		iM4a := strings.EqualFold(ai.Ext, "m4a") || strings.Contains(ai.ACodec, "mp4a")
		jM4a := strings.EqualFold(aj.Ext, "m4a") || strings.Contains(aj.ACodec, "mp4a")
		if iM4a != jM4a {
			return iM4a
		}
		if ai.Abr != aj.Abr {
			return ai.Abr > aj.Abr
		}
		return ai.Tbr > aj.Tbr
	})
	return &audio[0]
}

// betterCandidate 决定同一高度下哪条候选更值得交给播放器:先看编码
// (硬解兼容性),再看帧率,再看是否音视频合一,最后看码率。
func betterCandidate(a, b playbackCandidate) bool {
	if ar, br := codecRank(a.format.VCodec), codecRank(b.format.VCodec); ar != br {
		return ar < br
	}
	if a.format.FPS != b.format.FPS {
		return a.format.FPS > b.format.FPS
	}
	if a.kind != b.kind {
		return a.kind < b.kind
	}
	return a.format.Tbr > b.format.Tbr
}

// audioMimeType 返回独立音轨的容器类型;空字符串表示交给播放器自行嗅探。
func audioMimeType(f ytdlpFormat) string {
	if strings.EqualFold(f.Ext, "m4a") || strings.Contains(f.ACodec, "mp4a") {
		return "audio/mp4"
	}
	if strings.EqualFold(f.Ext, "webm") || strings.Contains(f.ACodec, "opus") {
		return "audio/webm"
	}
	return ""
}

func streamMimeType(f ytdlpFormat) string {
	if isHLS(f) {
		return "application/x-mpegurl"
	}
	if strings.EqualFold(f.Ext, "webm") {
		return "video/webm"
	}
	return "video/mp4"
}

// buildYouTubePlayback 从 yt-dlp 的 formats 中挑选可播放流,生成播放选项。
//
// 选择策略(App 播放器为 ExoPlayer/Media3,TV 盒子硬解以 H.264 为主):
//  1. 每档高度只保留一条最佳候选(编码 > 帧率 > 合流方式 > 码率);
//  2. YouTube 的 1080P+ 档位通常只有「纯视频轨 + 纯音频轨」DASH 格式,
//     服务端把最高码率音轨作为 AudioURL 一并下发,App 端合流播放;
//  3. 纯视频轨且没有可用音轨的候选直接丢弃——宁可少一档,也不返回无声视频;
//  4. progressive/HLS 合一流优先,避免额外的音画同步成本。
//
// 输出按高度降序排列;qn 直接采用视频高度(1080/720/...),App 端切清晰度
// 直接在 options 内本地换源,不需要重新解析。
func buildYouTubePlayback(info ytdlpInfo) PlaybackResultResponse {
	var muxed, muxedHLS, videoOnly, audioOnly []ytdlpFormat
	for _, f := range info.Formats {
		if f.URL == "" {
			continue
		}
		video, audio := hasVideoTrack(f), hasAudioTrack(f)
		switch {
		case video && audio:
			if isHLS(f) {
				muxedHLS = append(muxedHLS, f)
			} else if f.Height > 0 {
				muxed = append(muxed, f)
			}
		case video && !audio:
			if f.Height > 0 {
				videoOnly = append(videoOnly, f)
			}
		case !video && audio:
			audioOnly = append(audioOnly, f)
		}
	}

	bestAudio := bestAudioFormat(audioOnly)
	byHeight := make(map[int]playbackCandidate, len(muxed)+len(muxedHLS)+len(videoOnly))
	consider := func(c playbackCandidate) {
		if c.format.Height <= 0 {
			return
		}
		if cur, ok := byHeight[c.format.Height]; !ok || betterCandidate(c, cur) {
			byHeight[c.format.Height] = c
		}
	}
	for _, f := range muxed {
		consider(playbackCandidate{format: f, kind: streamMuxedProgressive})
	}
	for _, f := range muxedHLS {
		consider(playbackCandidate{format: f, kind: streamMuxedHLS})
	}
	if bestAudio != nil {
		for _, f := range videoOnly {
			kind := streamSplitProgressive
			if isHLS(f) {
				kind = streamSplitHLS
			}
			consider(playbackCandidate{format: f, audio: bestAudio, kind: kind})
		}
	}

	heights := make([]int, 0, len(byHeight))
	for h := range byHeight {
		heights = append(heights, h)
	}
	sort.Sort(sort.Reverse(sort.IntSlice(heights)))

	options := make([]PlaybackOptionResponse, 0, len(heights))
	qualities := make([]QualityOptionResponse, 0, len(heights))
	for _, h := range heights {
		c := byHeight[h]
		f := c.format
		id := "yt-" + info.ID + "-" + f.FormatID
		var audioURL, audioMime string
		if c.audio != nil {
			id += "-a" + c.audio.FormatID
			audioURL = c.audio.URL
			audioMime = audioMimeType(*c.audio)
		}
		label := heightLabel(h, f.FPS)
		options = append(options, PlaybackOptionResponse{
			ID:            id,
			Kind:          "vod",
			Label:         label,
			URL:           f.URL,
			AudioURL:      audioURL,
			AudioMimeType: audioMime,
			MimeType:      streamMimeType(f),
			Headers:       map[string]string{},
			QN:            h,
		})
		qualities = append(qualities, QualityOptionResponse{QN: h, Label: label})
	}
	var duration int64
	if info.Duration > 0 {
		duration = int64(info.Duration)
	}
	return PlaybackResultResponse{Options: options, AvailableQualities: qualities, DurationSeconds: duration}
}

// canonicalHeight 把 YouTube 的非标准编码高度(如 1078/1438)映射到商用
// 清晰度档位,避免 UI 出现 "1078P" 这类标签。
func canonicalHeight(height int) int {
	switch {
	case height >= 2000:
		return 2160
	case height >= 1300:
		return 1440
	case height >= 1000:
		return 1080
	case height >= 600:
		return 720
	case height >= 400:
		return 480
	case height >= 300:
		return 360
	case height >= 200:
		return 240
	default:
		return 144
	}
}

// heightLabel 生成清晰度展示标签:>=50fps 标注帧率,与 B 站 "1080P60" 风格一致。
func heightLabel(height int, fps float64) string {
	label := strconv.Itoa(canonicalHeight(height)) + "P"
	if fps >= 50 {
		label += strconv.Itoa(int(fps + 0.5))
	}
	return label
}
