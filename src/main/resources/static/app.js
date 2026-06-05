const { useState, useEffect, useRef, useMemo } = React;

mermaid.initialize({
    startOnLoad: false,
    theme: "default",
    securityLevel: "loose",
    sequence: { useMaxWidth: true, showSequenceNumbers: false }
});

let mermaidCounter = 0;

function MermaidDiagram({ code }) {
    const ref = useRef(null);
    const [error, setError] = useState(null);

    useEffect(() => {
        let cancelled = false;
        const id = "mmd-" + (mermaidCounter++);
        mermaid.render(id, code)
            .then(({ svg }) => {
                if (!cancelled && ref.current) {
                    ref.current.innerHTML = svg;
                }
            })
            .catch((e) => {
                if (!cancelled) setError(String(e));
            });
        return () => { cancelled = true; };
    }, [code]);

    if (error) {
        return <pre className="code">Could not render diagram:{"\n"}{error}{"\n\n"}{code}</pre>;
    }
    return <div className="mermaid-wrap" ref={ref} />;
}

function FlowCard({ flow }) {
    const [showSrc, setShowSrc] = useState(false);
    return (
        <div className="card flow-card">
            <div className="flow-head">
                <h4>{flow.name}</h4>
                <span className="toggle-src" onClick={() => setShowSrc(s => !s)}>
                    {showSrc ? "hide source" : "view mermaid source"}
                </span>
            </div>
            <div className="flow-desc">{flow.description}</div>
            <MermaidDiagram code={flow.mermaid} />
            {showSrc && <pre className="code" style={{ marginTop: 12 }}>{flow.mermaid}</pre>}
        </div>
    );
}

function ArchitectureTab({ arch }) {
    const maxLines = Math.max(1, ...arch.languages.map(l => l.lines));
    const html = useMemo(() => marked.parse(arch.explanation || ""), [arch.explanation]);
    return (
        <div>
            <div className="stats-grid">
                <div className="stat"><div className="num">{arch.totalFiles}</div><div className="lbl">Source files</div></div>
                <div className="stat"><div className="num">{arch.totalLinesOfCode.toLocaleString()}</div><div className="lbl">Lines of code</div></div>
                <div className="stat"><div className="num">{arch.languages.length}</div><div className="lbl">Languages</div></div>
                <div className="stat"><div className="num">{arch.frameworks.length}</div><div className="lbl">Frameworks</div></div>
            </div>

            <div className="card section">
                <h3>Architecture explanation</h3>
                <div className="markdown" dangerouslySetInnerHTML={{ __html: html }} />
            </div>

            <div className="card section">
                <h3>Frameworks &amp; build tools</h3>
                <div className="chips" style={{ marginBottom: 14 }}>
                    {arch.frameworks.length === 0 && <span className="empty">None detected</span>}
                    {arch.frameworks.map((f, i) => <span key={i} className="chip accent">{f}</span>)}
                </div>
                <div className="chips">
                    {arch.buildTools.map((b, i) => <span key={i} className="chip">{b}</span>)}
                </div>
            </div>

            <div className="card section">
                <h3>Languages</h3>
                {arch.languages.map((l, i) => (
                    <div key={i} className="lang-bar">
                        <div className="top"><span>{l.language}</span><span>{l.files} files · {l.lines.toLocaleString()} LOC</span></div>
                        <div className="track"><div className="fill" style={{ width: (l.lines / maxLines * 100) + "%" }} /></div>
                    </div>
                ))}
            </div>

            <div className="card section">
                <h3>Layers detected</h3>
                <div className="chips">
                    {arch.layers.length === 0 && <span className="empty">No standard layers detected</span>}
                    {arch.layers.map((l, i) => <span key={i} className="chip">{l}</span>)}
                </div>
            </div>

            <div className="card section">
                <h3>Module structure</h3>
                <pre className="tree">{arch.moduleTree || "(empty)"}</pre>
            </div>
        </div>
    );
}

function FlowsTab({ flows }) {
    if (!flows || flows.length === 0) {
        return <div className="card"><div className="empty">No code-flow sequence diagrams were generated for this repository.</div></div>;
    }
    return <div>{flows.map((f, i) => <FlowCard key={i} flow={f} />)}</div>;
}

function DbTab({ db }) {
    return (
        <div>
            <div className="card section">
                <h3>Summary</h3>
                <div className="markdown">{db.ormSummary}</div>
            </div>

            <div className="card section">
                <h3>Queries <span className="location">({db.queries.length})</span></h3>
                {db.queries.length === 0 ? <div className="empty">No queries detected</div> : (
                    <table className="data">
                        <thead><tr><th>Op</th><th>Kind</th><th>Query / statement</th><th>Location</th></tr></thead>
                        <tbody>
                            {db.queries.map((q, i) => (
                                <tr key={i}>
                                    <td><span className={"tag " + (q.operation || "UNKNOWN")}>{q.operation}</span></td>
                                    <td>{q.kind}</td>
                                    <td><code>{q.snippet}</code></td>
                                    <td className="location">{q.location}</td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                )}
            </div>

            <div className="card section">
                <h3>Stored procedures <span className="location">({db.storedProcedures.length})</span></h3>
                {db.storedProcedures.length === 0 ? <div className="empty">No stored procedures detected</div> : (
                    <table className="data">
                        <thead><tr><th>Name</th><th>Kind</th><th>Reference</th><th>Location</th></tr></thead>
                        <tbody>
                            {db.storedProcedures.map((p, i) => (
                                <tr key={i}>
                                    <td><code>{p.name}</code></td>
                                    <td><span className="tag method">{p.kind}</span></td>
                                    <td><code>{p.snippet}</code></td>
                                    <td className="location">{p.location}</td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                )}
            </div>
        </div>
    );
}

function OutputsTab({ outputs }) {
    if (!outputs || outputs.length === 0) {
        return <div className="card"><div className="empty">No HTTP endpoints / outputs detected.</div></div>;
    }
    return (
        <div className="card">
            <h3>Endpoint outputs <span className="location">({outputs.length})</span></h3>
            <table className="data">
                <thead><tr><th>Method</th><th>Path</th><th>Handler</th><th>Returns (output)</th><th>Location</th></tr></thead>
                <tbody>
                    {outputs.map((o, i) => (
                        <tr key={i}>
                            <td><span className={"tag " + (o.httpMethod || "ANY")}>{o.httpMethod}</span></td>
                            <td><code>{o.path}</code></td>
                            <td>{o.handler}</td>
                            <td><code>{o.returnType}</code></td>
                            <td className="location">{o.location}</td>
                        </tr>
                    ))}
                </tbody>
            </table>
        </div>
    );
}

function App() {
    const [repoUrl, setRepoUrl] = useState("");
    const [branch, setBranch] = useState("");
    const [username, setUsername] = useState("");
    const [token, setToken] = useState("");
    const [showAuth, setShowAuth] = useState(false);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState(null);
    const [result, setResult] = useState(null);
    const [tab, setTab] = useState("arch");

    async function analyze() {
        if (!repoUrl.trim()) { setError("Please enter a git repository URL."); return; }
        setLoading(true); setError(null); setResult(null);
        try {
            const res = await fetch("/api/analyze", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    repoUrl: repoUrl.trim(),
                    branch: branch.trim(),
                    username: username.trim(),
                    token: token.trim()
                })
            });
            const data = await res.json();
            if (!res.ok) throw new Error(data.error || "Analysis failed");
            setResult(data);
            setTab("arch");
        } catch (e) {
            const msg = String(e.message || e);
            const authIssue = /auth|401|403|not authorized|denied|credential/i.test(msg);
            setError(authIssue && !token
                ? msg + "  —  This looks like a private repo. Click \"Private repo?\" below and add a personal access token."
                : msg);
            if (authIssue && !token) setShowAuth(true);
        } finally {
            setLoading(false);
        }
    }

    function onKey(e) { if (e.key === "Enter") analyze(); }
    function useExample(url) { setRepoUrl(url); }

    const tabs = result ? [
        { id: "arch", label: "Architecture" },
        { id: "flows", label: "Code flows", count: result.flows.length },
        { id: "db", label: "Database", count: result.dbUsage.queries.length + result.dbUsage.storedProcedures.length },
        { id: "outputs", label: "Outputs", count: result.outputs.length },
    ] : [];

    return (
        <div className="app">
            <div className="header">
                <h1>Arch Helper</h1>
                <p>Paste a git repo URL to generate code-flow sequence diagrams, an architecture explanation, and its DB query / stored-procedure usage.</p>
            </div>

            <div className="card">
                <div className="search-row">
                    <div className="grow">
                        <input className="input" placeholder="https://github.com/owner/repo  (or owner/repo)"
                               value={repoUrl} onChange={e => setRepoUrl(e.target.value)} onKeyDown={onKey} />
                    </div>
                    <input className="input branch" placeholder="branch (optional)"
                           value={branch} onChange={e => setBranch(e.target.value)} onKeyDown={onKey} />
                    <button className="btn" onClick={analyze} disabled={loading}>
                        {loading ? "Analyzing…" : "Analyze"}
                    </button>
                </div>
                <div className="examples">
                    Try:
                    <a onClick={() => useExample("https://github.com/spring-projects/spring-petclinic")}>spring-petclinic</a>
                    <a onClick={() => useExample("https://github.com/spring-guides/gs-rest-service")}>gs-rest-service</a>
                    <a style={{ float: "right" }} onClick={() => setShowAuth(s => !s)}>
                        {showAuth ? "▲ hide credentials" : "🔒 Private repo?"}
                    </a>
                </div>

                {showAuth && (
                    <div className="auth-box">
                        <div className="auth-hint">
                            For a private HTTPS repo, provide a <strong>personal access token</strong> (GitHub: Settings → Developer settings → Tokens, with <code>repo</code> scope). Username is optional. Credentials are used only for this clone and never stored.
                        </div>
                        <div className="search-row">
                            <input className="input" style={{ flex: "0 0 200px" }} placeholder="username (optional)"
                                   value={username} onChange={e => setUsername(e.target.value)} onKeyDown={onKey} />
                            <input className="input grow" type="password" placeholder="personal access token"
                                   value={token} onChange={e => setToken(e.target.value)} onKeyDown={onKey} />
                        </div>
                    </div>
                )}

                {error && <div className="error">{error}</div>}
            </div>

            {loading && (
                <div className="loading">
                    <div className="spinner" />
                    Cloning and analyzing the repository… this can take a little while for large repos.
                </div>
            )}

            {result && !loading && (
                <div className="results">
                    <div className="repo-title">
                        <h2>{result.repoName}</h2>
                        <span className="branch-chip">branch: {result.defaultBranch}</span>
                        <span className="branch-chip">{result.repoUrl}</span>
                    </div>

                    {result.warnings && result.warnings.length > 0 && (
                        <div className="warnings">
                            {result.warnings.map((w, i) => <div key={i} className="w">{w}</div>)}
                        </div>
                    )}

                    <div className="tabs">
                        {tabs.map(t => (
                            <div key={t.id} className={"tab " + (tab === t.id ? "active" : "")} onClick={() => setTab(t.id)}>
                                {t.label}{typeof t.count === "number" && <span className="count">{t.count}</span>}
                            </div>
                        ))}
                    </div>

                    {tab === "arch" && <ArchitectureTab arch={result.architecture} />}
                    {tab === "flows" && <FlowsTab flows={result.flows} />}
                    {tab === "db" && <DbTab db={result.dbUsage} />}
                    {tab === "outputs" && <OutputsTab outputs={result.outputs} />}
                </div>
            )}
        </div>
    );
}

ReactDOM.createRoot(document.getElementById("root")).render(<App />);
