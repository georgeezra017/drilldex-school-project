import { defineConfig } from "vite";
import react from "@vitejs/plugin-react-swc";
import path from "path";
import { fileURLToPath } from "url";

// Convert import.meta.url to __dirname equivalent
const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      react: path.resolve(__dirname, "node_modules/react"),
      "react-dom": path.resolve(__dirname, "node_modules/react-dom"),
    },
  },
  server: {
    proxy: {
      "/api": "http://localhost:8080",
      "/uploads": "http://localhost:8080",
      "/licenses": "http://localhost:8080",
    },
  },
});
