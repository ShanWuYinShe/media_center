import { InternetArchiveProvider } from "../src/providers/InternetArchiveProvider.js";

function jsonResponse(data: unknown): Response {
  return new Response(JSON.stringify(data), {
    status: 200,
    headers: { "content-type": "application/json" },
  });
}

test("Internet Archive 搜索使用 advancedsearch 官方接口", async () => {
  const calls: string[] = [];
  const provider = new InternetArchiveProvider(async (input) => {
    calls.push(String(input));
    return jsonResponse({
      response: {
        numFound: 1,
        docs: [
          {
            identifier: "classic-chinese-film",
            title: "Classic Chinese Film",
            description: "public domain feature film",
            creator: ["Archive"],
            year: "1941",
            runtime: "1:30:00",
          },
        ],
      },
    });
  });

  const result = await provider.search("public domain feature film", 1, 10);

  expect(calls[0]).toContain("https://archive.org/advancedsearch.php");
  expect(calls[0]).toContain("mediatype%3Amovies");
  expect(result.items).toHaveLength(1);
  expect(result.items[0]).toMatchObject({
    provider: "internet-archive",
    id: "classic-chinese-film",
    title: "Classic Chinese Film",
    year: "1941",
    durationSeconds: 5400,
    playable: true,
  });
});

test("Internet Archive 详情从 metadata 文件列表生成点播流", async () => {
  const provider = new InternetArchiveProvider(async () =>
    jsonResponse({
      metadata: {
        identifier: "classic-chinese-film",
        title: "Classic Chinese Film",
        description: "A <b>public domain</b> film",
        creator: "Archive",
        year: "1941",
        licenseurl: "https://creativecommons.org/publicdomain/mark/1.0/",
      },
      files: [
        {
          name: "classic.mp4",
          format: "MPEG4",
          size: "2048",
          width: "1280",
          height: "720",
        },
        {
          name: "classic.ogv",
          format: "Ogg Video",
        },
      ],
    }),
  );

  const result = await provider.getDetails("classic-chinese-film");

  expect(result).toMatchObject({
    provider: "internet-archive",
    id: "classic-chinese-film",
    title: "Classic Chinese Film",
    description: "A public domain film",
    creators: ["Archive"],
    licenseUrl: "https://creativecommons.org/publicdomain/mark/1.0/",
    sourceUrl: "https://archive.org/details/classic-chinese-film",
    playable: true,
  });
  expect(result.streams).toEqual([
    {
      id: "ia-classic-chinese-film-classic.mp4",
      url: "https://archive.org/download/classic-chinese-film/classic.mp4",
      originalUrl: "https://archive.org/download/classic-chinese-film/classic.mp4",
      mimeType: "video/mp4",
      quality: "720P",
      width: 1280,
      height: 720,
      sizeBytes: 2048,
      supportsRange: true,
    },
  ]);
});
