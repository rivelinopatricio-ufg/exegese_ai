/*
 * Tailwind CSS configuration for Exegese AI.
 *
 * Reproduces the configuration that used to be declared inline for the Tailwind Play CDN.
 * The generated stylesheet (src/main/resources/static/css/tailwind.css) is committed, so the
 * Maven build does not need Node.js. Rebuild it after changing classes in templates or scripts:
 *
 *   cd src/main/frontend && npm ci && npm run build:css
 */
/** @type {import('tailwindcss').Config} */
module.exports = {
  darkMode: 'class',
  // Class names built in JavaScript (chat bubbles, citation chips, error lines) are scanned too
  content: [
    '../resources/templates/**/*.html',
    '../resources/static/js/**/*.js'
  ],
  theme: {
    extend: {
      colors: {
        navy: { 900: '#0b1120', 950: '#070b14' }
      }
    }
  },
  plugins: []
};
