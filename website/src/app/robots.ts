import type { MetadataRoute } from "next";

const resolvedHost =
  process.env.VERCEL_PROJECT_PRODUCTION_URL ||
  process.env.VERCEL_URL ||
  "lumyn-web.vercel.app";

export default function robots(): MetadataRoute.Robots {
  return {
    rules: {
      userAgent: "*",
      allow: "/",
    },
    sitemap: `https://${resolvedHost}/sitemap.xml`,
  };
}
