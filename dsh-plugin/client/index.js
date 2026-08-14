// DSH IDE Bridge - client half (permanent mount).
// While a session is open, polls GET /ide/peek once a second and fills the
// native composer draft via the inputActions standard prop of the
// conversation.input.dock slot, then shows a review notice row.
window.__ModuleLoader__.load({
  id: "dsh-ide-bridge",
  factory: (require) => {
    var module = { exports: {} };
    var exports = module.exports;
    var React = require("react");

    var NOTICE_STYLE = {
      display: "flex",
      alignItems: "center",
      gap: "10px",
      boxSizing: "border-box",
      width: "100%",
      maxWidth: "var(--dsh-composer-card-max-width, 780px)",
      margin: "0 auto 6px",
      padding: "6px 12px",
      border: "1px solid var(--dsw-alias-border-l1, #333)",
      borderRadius: "10px",
      background: "var(--dsw-specific-tip, transparent)",
      fontSize: "13px",
      lineHeight: "20px",
      color: "var(--dsw-alias-label-primary, inherit)",
    };
    var BUTTON_STYLE = {
      marginLeft: "auto",
      cursor: "pointer",
      border: "1px solid var(--dsw-alias-border-l1, #333)",
      background: "transparent",
      borderRadius: "6px",
      padding: "2px 10px",
      fontSize: "12px",
      lineHeight: "18px",
      color: "var(--dsw-alias-label-secondary, inherit)",
    };

    function peek(sessionId) {
      return fetch("/ide/peek?sessionId=" + encodeURIComponent(sessionId), { credentials: "same-origin" })
        .then(function (res) { return res.ok ? res.json() : null; })
        .catch(function () { return null; });
    }

    function Dock(props) {
      var noticeState = React.useState(null);
      var notice = noticeState[0];
      var setNotice = noticeState[1];
      var actionsRef = React.useRef(props && props.inputActions);
      actionsRef.current = props && props.inputActions;

      React.useEffect(function () {
        var sessionId = props && props.sessionId;
        if (typeof sessionId !== "string" || sessionId === "") return undefined;
        var disposed = false;
        var dismiss = null;
        var timer = setInterval(function () {
          if (disposed) return;
          peek(sessionId).then(function (draft) {
            if (disposed || draft === null || draft === undefined) return;
            var actions = actionsRef.current;
            if (actions && typeof actions.setDraft === "function") actions.setDraft(draft.text);
            setNotice({ id: draft.draftId });
            if (dismiss !== null) clearTimeout(dismiss);
            dismiss = setTimeout(function () { if (!disposed) setNotice(null); }, 8000);
          });
        }, 1000);
        return function () {
          disposed = true;
          clearInterval(timer);
          if (dismiss !== null) clearTimeout(dismiss);
        };
      }, [props && props.sessionId]);

      function clear() {
        var actions = actionsRef.current;
        if (actions && typeof actions.setDraft === "function") actions.setDraft("");
        setNotice(null);
      }

      if (notice === null) return null;
      return React.createElement(
        "div",
        { style: NOTICE_STYLE },
        React.createElement("span", null, "已从 IDEA 收到代码上下文，已填入输入框，请审阅后发送"),
        React.createElement("button", { type: "button", style: BUTTON_STYLE, onClick: clear }, "清空"),
      );
    }

    function apply(ctx) {
      var slots = ctx.get("slots");
      if (slots === undefined) return;
      ctx.effect(function () {
        return slots.inject("conversation.input.dock", function () {
          return slots.register(
            { name: "conversation.input.dock", id: "ide-bridge", order: 30, label: "IDE Bridge" },
            function (props) { return React.createElement(Dock, props); },
          );
        });
      }, "dsh-ide-bridge dock entry");
    }

    exports.inject = ["slots"];
    exports.apply = apply;
    return module.exports;
  },
});
