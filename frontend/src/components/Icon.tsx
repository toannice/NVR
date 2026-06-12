// --- Icons --- //
export const Icon = ({ name, className = "" }: { name: string, className?: string }) => (
  <span className={`material-icons ${className} `}>{name}</span>
)
