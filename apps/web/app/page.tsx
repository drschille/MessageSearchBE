const featureCards = [
  {
    title: "Search + Read",
    body: "Paragraph-level results with snapshot citations and language filters."
  },
  {
    title: "Editorial Workflow",
    body: "Draft, review, publish, and archive states with clear role gating."
  },
  {
    title: "Collaboration",
    body: "CRDT-backed editing with WebSocket presence indicators."
  }
];

export default function Home() {
  return (
    <main>
      <div className="container">
        <span className="tag">Frontend MVP</span>
        <h1>MessageSearch Web</h1>
        <p>
          This project is scaffolded. Connect the API client, then build the
          search and editorial flows defined in apps/web/SPEC.md.
        </p>
        <p>
          Admins can start with the user management panel at{" "}
          <a href="/admin/users">/admin/users</a>.
        </p>
        <div className="grid">
          {featureCards.map((card) => (
            <section className="card" key={card.title}>
              <h3>{card.title}</h3>
              <p>{card.body}</p>
            </section>
          ))}
        </div>
        <footer>
          Update NEXT_PUBLIC_API_BASE_URL in apps/web/.env.local to target the
          backend.
        </footer>
      </div>
    </main>
  );
}
