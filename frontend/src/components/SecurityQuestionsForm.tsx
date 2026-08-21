import { useState } from 'react';
import { t } from '../i18n';

export interface CatalogQuestion { id: string; text: string }
export interface AnswerItem { questionId: string; answer: string }

interface Props {
    catalog: CatalogQuestion[];
    /** Pre-selected question ids (settings screen re-selects what is already configured). */
    initialIds?: string[];
    onSubmit: (items: AnswerItem[]) => void | Promise<void>;
    onCancel?: () => void;
    cancelLabel?: string;
    submitLabel?: string;
    busy?: boolean;
    error?: string;
    variant?: 'dark' | 'light';
}

const SLOTS = 3;

export default function SecurityQuestionsForm({
    catalog,
    initialIds,
    onSubmit,
    onCancel,
    cancelLabel = t('Bỏ qua'),
    submitLabel = t('Lưu'),
    busy = false,
    error,
    variant = 'dark',
}: Props) {
    const [ids, setIds] = useState<string[]>(() => {
        const base = initialIds ?? [];
        return Array.from({ length: SLOTS }, (_, i) => base[i] ?? '');
    });
    const [answers, setAnswers] = useState<string[]>(Array(SLOTS).fill(''));

    /**
     * Each dropdown offers the catalog minus whatever the *other* slots hold, so the same
     * question can never be picked twice.
     */
    const optionsFor = (slot: number) =>
        catalog.filter((q) => q.id === ids[slot] || !ids.some((chosen, i) => i !== slot && chosen === q.id));

    const changeQuestion = (slot: number, value: string) => {
        setIds((prev) => {
            const next = [...prev];
            next[slot] = value;
            // Picking a question another slot already holds steals it — clear that slot
            // (and its answer) instead of silently allowing a duplicate.
            if (value) {
                prev.forEach((old, i) => {
                    if (i !== slot && old === value) {
                        next[i] = '';
                        setAnswers((a) => {
                            const na = [...a];
                            na[i] = '';
                            return na;
                        });
                    }
                });
            }
            return next;
        });
    };

    const changeAnswer = (slot: number, value: string) => {
        setAnswers((prev) => {
            const next = [...prev];
            next[slot] = value;
            return next;
        });
    };

    const complete =
        ids.every((id) => id) &&
        new Set(ids).size === SLOTS &&
        answers.every((a) => a.trim().length >= 2);

    const dark = variant === 'dark';
    const labelCls = dark ? 'text-white/70' : 'text-gray-600 dark:text-gray-300';
    const fieldCls = dark
        ? 'w-full bg-white/10 border border-white/20 rounded-lg px-3 py-2 text-white text-sm focus:outline-none focus:ring-2 focus:ring-blue-500'
        : 'w-full bg-white dark:bg-gray-900 border border-gray-300 dark:border-gray-600 rounded-lg px-3 py-2 text-gray-900 dark:text-white text-sm focus:outline-none focus:ring-2 focus:ring-blue-500';

    return (
        <form
            className="w-full flex flex-col gap-3"
            onSubmit={(e) => {
                e.preventDefault();
                if (!complete || busy) return;
                onSubmit(ids.map((questionId, i) => ({ questionId, answer: answers[i] })));
            }}
        >
            {Array.from({ length: SLOTS }, (_, slot) => (
                <div key={slot} className="flex flex-col gap-1.5">
                    <span className={`text-xs font-medium ${labelCls}`}>Câu {slot + 1}</span>
                    <select
                        value={ids[slot]}
                        onChange={(e) => changeQuestion(slot, e.target.value)}
                        className={`${fieldCls} ${dark ? '[&>option]:bg-slate-800' : ''}`}
                        disabled={busy}
                    >
                        <option value="">{t("— Chọn câu hỏi —")}</option>
                        {optionsFor(slot).map((q) => (
                            <option key={q.id} value={q.id}>{q.text}</option>
                        ))}
                    </select>
                    <input
                        type="text"
                        value={answers[slot]}
                        onChange={(e) => changeAnswer(slot, e.target.value)}
                        placeholder={t("Câu trả lời")}
                        className={fieldCls}
                        disabled={busy || !ids[slot]}
                        autoComplete="off"
                    />
                </div>
            ))}

            <p className={`text-[11px] leading-relaxed ${dark ? 'text-white/40' : 'text-gray-500 dark:text-gray-400'}`}>{t("Không phân biệt hoa thường và dấu tiếng Việt — “Hà Nội” và “ha noi” được coi là giống nhau.")}</p>

            {error && <p className="text-xs text-red-400">{error}</p>}

            <div className="flex gap-2 mt-1">
                {onCancel && (
                    <button
                        type="button"
                        onClick={onCancel}
                        disabled={busy}
                        className={`flex-1 py-2.5 rounded-xl text-sm font-medium transition-colors ${dark ? 'bg-white/10 hover:bg-white/20 text-white/80' : 'bg-gray-100 dark:bg-gray-700 hover:bg-gray-200 dark:hover:bg-gray-600 text-gray-700 dark:text-gray-200'}`}
                    >
                        {cancelLabel}
                    </button>
                )}
                <button
                    type="submit"
                    disabled={!complete || busy}
                    className="flex-1 bg-blue-600 hover:bg-blue-500 disabled:opacity-40 disabled:cursor-not-allowed text-white font-bold py-2.5 rounded-xl text-sm transition-all"
                >
                    {busy ? t('Đang lưu...') : submitLabel}
                </button>
            </div>
        </form>
    );
}
