// The living gradient-mesh + grain. Hues are driven by the --hue-* CSS vars
// set on :root from App as the current track changes.
export default function Background() {
  return (
    <>
      <div className="mesh" aria-hidden>
        <div className="blob" />
        <div className="blob-2" />
      </div>
      <div className="grain" aria-hidden />
    </>
  )
}
