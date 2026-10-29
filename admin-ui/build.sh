# Builds the admin UI for serving by the ScaleGuard server at /adminui (see DefaultResponseHandler).
# For hosting at a site root (e.g. Vercel), run plain `npm run build` instead.
PUBLIC_URL=/adminui npm run build
mkdir -p ../adminui
rm -rf ../adminui/static
cp -r build/* ../adminui/
