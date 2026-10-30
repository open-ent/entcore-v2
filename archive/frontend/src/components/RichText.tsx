import { Fragment } from "react";

/** Rend un libellé i18n contenant des balises <br> et <strong> sans injecter de HTML (pas de dangerouslySetInnerHTML). */
export const RichText = ({ text }: { text: string }) => (
  <>
    {text.split(/<br\s*\/?>/i).map((line, i) => (
      <Fragment key={i}>
        {i > 0 && <br />}
        {line.split(/(<strong>.*?<\/strong>)/i).map((part, j) => {
          const m = /^<strong>(.*)<\/strong>$/i.exec(part);
          return m ? <strong key={j}>{m[1]}</strong> : <Fragment key={j}>{part}</Fragment>;
        })}
      </Fragment>
    ))}
  </>
);
