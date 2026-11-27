import type { MetadataRoute } from "next";

const resolvedHost =
  process.env.VERCEL_PROJECT_PRODUCTION_URL ||
  process.env.VERCEL_URL ||
  "lunarias.vercel.app";

export default function sitemap(): MetadataRoute.Sitemap {
  const base = `https://${resolvedHost}`;
  return [
    {
      url: base,
      lastModified: new Date("2026-11-26"),
      changeFrequency: "weekly",
      priority: 1,
    },
    {
      url: `${base}/privacy`,
      lastModified: new Date("2026-11-26"),
      changeFrequency: "monthly",
      priority: 0.6,
    },
  ];
}
