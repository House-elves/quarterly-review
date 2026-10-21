// quarterly-review's tab on Elf Desk: it reads a quarter of your GitHub work (and the weekly statuses you sent) and
// writes it up the way your Quarterly Accomplishments doc reads. You add what GitHub doesn't know (talks, PTO), then
// copy it into the doc. Nothing is sent anywhere.
import {
    LitElement, html, css, shared, markdown, markdownHtml, markdownStyles, runTime, copyText, copyHtml, settingsButton,
    Ticking, elf,
} from '/elf-kit/1/kit.js';

const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October',
    'November', 'December'];
const ID = /^(\d{4})-(\d{2})-(\d{2})_(\d{2})-(\d{2})-(\d{2})\.json$/;

/** "2026-Q3" for the quarter {@code back} quarters before the one {@code now} is in. */
function quarterOf(now = new Date(), back = 0) {
    let q = Math.floor(now.getMonth() / 3) - back;
    let year = now.getFullYear();
    while (q < 0) { q += 4; year--; }
    return `${year}-Q${q + 1}`;
}

/** "Q3 2026, July to September". */
function quarterName(quarter) {
    const [year, q] = quarter.split('-Q').map(Number);
    return `Q${q} ${year}, ${MONTHS[(q - 1) * 3]} to ${MONTHS[(q - 1) * 3 + 2]}`;
}

function atOf(file) {
    const m = ID.exec(file);
    return m ? new Date(+m[1], m[2] - 1, +m[3], +m[4], +m[5], +m[6]) : null;
}

class QuarterlyReview extends LitElement {

    static properties = {
        _reviews: { state: true },
        _review: { state: true },
        _picked: { state: true },
        _quarter: { state: true },
        _body: { state: true },
        _editing: { state: true },
        _loading: { state: true },
        _copied: { state: true },
        _error: { state: true },
    };

    static styles = [shared, markdownStyles, css`
        .page { display: grid; gap: 26px; }
        h1 { font-size: 34px; line-height: 1.15; font-weight: 600; letter-spacing: -0.022em; }
        .sub { margin: 6px 0 0; color: var(--muted); font-size: 14px; }
        .write { display: grid; gap: 12px; }
        .write label { display: inline-flex; gap: 8px; align-items: center; font-size: 14px; color: var(--muted); }
        .review { padding: 22px 26px; display: grid; gap: 16px; min-width: 0; }
        .acts { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }
        .note { margin: 0; font-size: 13px; color: var(--muted); max-width: 46rem; }
        textarea {
            width: 100%; box-sizing: border-box; min-height: 34rem; resize: vertical; margin: 0;
            font: 13.5px/1.6 var(--font-code); color: var(--ink); background: var(--surface-2);
            border: 1px solid var(--line); border-radius: var(--r-control); padding: 14px 16px;
        }
        /* The doc's look: the quarter as a light, large heading, projects as bold labels over their bullets. */
        .doc { max-width: 46rem; font-size: 14.5px; }
        .doc h1 { font-size: 26px; font-weight: 400; font-style: italic; color: var(--muted); margin: 0 0 14px; }
        .doc h2 { font-size: 15px; font-weight: 700; margin: 20px 0 6px; }
        .doc ul { padding-left: 22px; }
        .empty { color: var(--muted); padding: 18px; margin: 0; }
        .skeleton.big { height: 50vh; }
    `];

    constructor() {
        super();
        new Ticking(this);
        this._picked = null;
        this._quarter = quarterOf(new Date(), 1);
        this._loading = true;
    }

    connectedCallback() {
        super.connectedCallback();
        this._load();
    }

    async _load() {
        this._loading = true;
        this._error = null;
        try {
            const files = (await elf.list('runs')).filter(f => ID.test(f.name)).slice(0, 20).map(f => f.name);
            const heads = files.length ? await elf.read('runs', files, ['title']) : {};
            this._reviews = files.map(f => ({ id: f.slice(0, -5), file: f, at: atOf(f),
                label: `${runTime(atOf(f))}${heads[f]?.title ? ` (${heads[f].title})` : ''}` }));
            const pick = this._reviews.find(r => r.id === this._picked) ?? this._reviews[0];
            this._review = pick ? { ...(await elf.readJson('runs', pick.file)), id: pick.id, at: pick.at } : null;
            const r = this._review;
            this._body = r ? ((await elf.store.get(`edit.${r.id}`)) ?? r.body) : null;
            this._editing = false;
        } catch (e) {
            this._error = e.message;
        } finally {
            this._loading = false;
        }
    }

    _edit(e) {
        this._body = e.target.value;
        elf.store.set(`edit.${this._review.id}`, this._body === this._review.body ? null : this._body);
    }

    _discard() {
        elf.store.set(`edit.${this._review.id}`, null);
        this._body = this._review.body;
    }

    async _copy(rich) {
        try {
            if (rich) await copyHtml(markdownHtml(this._body), this._body);
            else await copyText(this._body);
            this._copied = rich ? 'rich' : 'text';
            setTimeout(() => { this._copied = null; }, 2000);
        } catch (e) {
            this._error = `Couldn't copy: ${e.message}`;
        }
    }

    _quarters() {
        const now = new Date();
        return [0, 1, 2, 3, 4].map(back => ({ id: quarterOf(now, back), now: back === 0 }));
    }

    _panel(r) {
        const edited = this._body !== r.body;
        return html`
            <section class="panel review" aria-label="The review">
                ${this._editing
                    ? html`<textarea aria-label="Review text (Markdown)" .value=${this._body ?? ''} @input=${this._edit}></textarea>`
                    : html`<div class="md doc">${markdown(this._body)}</div>`}
                <div class="acts">
                    <button class="primary" @click=${() => this._copy(true)}>${this._copied === 'rich' ? 'Copied' : 'Copy for the doc'}</button>
                    <button @click=${() => { this._editing = !this._editing; }}>${this._editing ? 'Done editing' : 'Edit'}</button>
                    <button class="quiet" @click=${() => this._copy(false)}>${this._copied === 'text' ? 'Copied' : 'Copy as Markdown'}</button>
                    ${edited ? html`<button class="quiet" @click=${this._discard}>Discard my edits</button>` : ''}
                </div>
                <p class="note">Add what GitHub doesn't know (talks, PTO) with Edit, then Copy for the doc and paste it at
                    the top of your Quarterly Accomplishments doc: headings and bullets paste as they are.
                    ${edited ? 'Your edits are kept in this browser.' : ''}</p>
            </section>`;
    }

    render() {
        const r = this._review;
        return html`
            <div class="page">
                <div class="page-top"><div>
                    <h1>Quarterly review</h1>
                    <p class="sub">
                        ${r ? html`${quarterName(r.quarter)}${r.until < r.end ? ` (up to ${r.until})` : ''}. Written
                                ${runTime(r.at)} from ${r.prs} merged PRs and ${r.issues} closed issues${r.weeklyStatuses
                                    ? `, and ${r.weeklyStatuses} weekly statuses` : ''}.`
                            : this._loading ? '' : 'No review written yet.'}
                    </p>
                </div>${settingsButton(null, 'Quarterly review settings')}</div>
                <div class="write">
                    <label>Quarter
                        <select aria-label="Quarter to review" @change=${e => { this._quarter = e.target.value; }}>
                            ${this._quarters().map(q => html`<option value=${q.id} ?selected=${q.id === this._quarter}>
                                ${quarterName(q.id)}${q.now ? ' (so far)' : ''}</option>`)}
                        </select>
                    </label>
                    <elf-run-bar command="review" label="Write the review" .runs=${this._reviews ?? []} .picked=${this._picked}
                        .params=${() => ({ quarter: this._quarter })}
                        note="Reads your merged PRs and closed issues for the quarter, and the weekly statuses you sent, and writes the review with Claude. Takes a few minutes. Sends nothing."
                        @run-picked=${e => { this._picked = e.detail; this._load(); }}
                        @run-finished=${() => { this._picked = null; this._load(); }}></elf-run-bar>
                </div>
                ${this._error ? html`<p class="error" role="alert">${this._error}</p>` : ''}
                ${this._loading && !r ? html`<div class="skeleton big"></div>` : ''}
                ${r ? this._panel(r) : !this._loading ? html`<p class="panel empty">Pick a quarter and write its review.</p>` : ''}
            </div>
        `;
    }
}

customElements.define('quarterly-review', QuarterlyReview);
document.body.append(document.createElement('quarterly-review'));
