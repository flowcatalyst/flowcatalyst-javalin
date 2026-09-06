import{X as K,ai as z,Y as I,z as O,$ as A,a0 as V,af as j,aX as T,aY as q,a5 as M,aa as L,a9 as U,A as x,ac as Z,o as s,j as C,l as h,g as f,m as c,q as D,ad as N,K as F,ae as u,F as Y,h as v,U as E,y as g,p as m,L as Q,M as $,aZ as X,a_ as H,c as W,e as G,u as J,i as _,t as ee,k as te,r as ne,x as re,_ as ae}from"./index-fpAKIggb.js";import{s as ie}from"./index-59Vs6L2q.js";import{s as oe}from"./index-CcP5Kbza.js";var se=`
    .p-drawer {
        display: flex;
        flex-direction: column;
        transform: translate3d(0px, 0px, 0px);
        position: relative;
        transition: transform 0.3s;
        background: dt('drawer.background');
        color: dt('drawer.color');
        border-style: solid;
        border-color: dt('drawer.border.color');
        box-shadow: dt('drawer.shadow');
    }

    .p-drawer-content {
        overflow-y: auto;
        flex-grow: 1;
        padding: dt('drawer.content.padding');
    }

    .p-drawer-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        flex-shrink: 0;
        padding: dt('drawer.header.padding');
    }

    .p-drawer-footer {
        padding: dt('drawer.footer.padding');
    }

    .p-drawer-title {
        font-weight: dt('drawer.title.font.weight');
        font-size: dt('drawer.title.font.size');
    }

    .p-drawer-full .p-drawer {
        transition: none;
        transform: none;
        width: 100vw !important;
        height: 100vh !important;
        max-height: 100%;
        top: 0px !important;
        left: 0px !important;
        border-width: 1px;
    }

    .p-drawer-left .p-drawer-enter-active {
        animation: p-animate-drawer-enter-left 0.5s cubic-bezier(0.32, 0.72, 0, 1);
    }
    .p-drawer-left .p-drawer-leave-active {
        animation: p-animate-drawer-leave-left 0.5s cubic-bezier(0.32, 0.72, 0, 1);
    }

    .p-drawer-right .p-drawer-enter-active {
        animation: p-animate-drawer-enter-right 0.5s cubic-bezier(0.32, 0.72, 0, 1);
    }
    .p-drawer-right .p-drawer-leave-active {
        animation: p-animate-drawer-leave-right 0.5s cubic-bezier(0.32, 0.72, 0, 1);
    }

    .p-drawer-top .p-drawer-enter-active {
        animation: p-animate-drawer-enter-top 0.5s cubic-bezier(0.32, 0.72, 0, 1);
    }
    .p-drawer-top .p-drawer-leave-active {
        animation: p-animate-drawer-leave-top 0.5s cubic-bezier(0.32, 0.72, 0, 1);
    }

    .p-drawer-bottom .p-drawer-enter-active {
        animation: p-animate-drawer-enter-bottom 0.5s cubic-bezier(0.32, 0.72, 0, 1);
    }
    .p-drawer-bottom .p-drawer-leave-active {
        animation: p-animate-drawer-leave-bottom 0.5s cubic-bezier(0.32, 0.72, 0, 1);
    }

    .p-drawer-full .p-drawer-enter-active {
        animation: p-animate-drawer-enter-full 0.5s cubic-bezier(0.32, 0.72, 0, 1);
    }
    .p-drawer-full .p-drawer-leave-active {
        animation: p-animate-drawer-leave-full 0.5s cubic-bezier(0.32, 0.72, 0, 1);
    }
    
    .p-drawer-left .p-drawer {
        width: 20rem;
        height: 100%;
        border-inline-end-width: 1px;
    }

    .p-drawer-right .p-drawer {
        width: 20rem;
        height: 100%;
        border-inline-start-width: 1px;
    }

    .p-drawer-top .p-drawer {
        height: 10rem;
        width: 100%;
        border-block-end-width: 1px;
    }

    .p-drawer-bottom .p-drawer {
        height: 10rem;
        width: 100%;
        border-block-start-width: 1px;
    }

    .p-drawer-left .p-drawer-content,
    .p-drawer-right .p-drawer-content,
    .p-drawer-top .p-drawer-content,
    .p-drawer-bottom .p-drawer-content {
        width: 100%;
        height: 100%;
    }

    .p-drawer-open {
        display: flex;
    }

    .p-drawer-mask:dir(rtl) {
        flex-direction: row-reverse;
    }

    @keyframes p-animate-drawer-enter-left {
        from {
            transform: translate3d(-100%, 0px, 0px);
        }
    }

    @keyframes p-animate-drawer-leave-left {
        to {
            transform: translate3d(-100%, 0px, 0px);
        }
    }

    @keyframes p-animate-drawer-enter-right {
        from {
            transform: translate3d(100%, 0px, 0px);
        }
    }

    @keyframes p-animate-drawer-leave-right {
        to {
            transform: translate3d(100%, 0px, 0px);
        }
    }

    @keyframes p-animate-drawer-enter-top {
        from {
            transform: translate3d(0px, -100%, 0px);
        }
    }

    @keyframes p-animate-drawer-leave-top {
        to {
            transform: translate3d(0px, -100%, 0px);
        }
    }

    @keyframes p-animate-drawer-enter-bottom {
        from {
            transform: translate3d(0px, 100%, 0px);
        }
    }

    @keyframes p-animate-drawer-leave-bottom {
        to {
            transform: translate3d(0px, 100%, 0px);
        }
    }

    @keyframes p-animate-drawer-enter-full {
        from {
            opacity: 0;
            transform: scale(0.93);
        }
    }

    @keyframes p-animate-drawer-leave-full {
        to {
            opacity: 0;
            transform: scale(0.93);
        }
    }
`,le={mask:function(t){var n=t.position,a=t.modal;return{position:"fixed",height:"100%",width:"100%",left:0,top:0,display:"flex",justifyContent:n==="left"?"flex-start":n==="right"?"flex-end":"center",alignItems:n==="top"?"flex-start":n==="bottom"?"flex-end":"center",pointerEvents:a?"auto":"none"}},root:{pointerEvents:"auto"}},de={mask:function(t){var n=t.instance,a=t.props,l=["left","right","top","bottom"],r=l.find(function(d){return d===a.position});return["p-drawer-mask",{"p-overlay-mask p-overlay-mask-enter-active":a.modal,"p-drawer-open":n.containerVisible,"p-drawer-full":n.fullScreen},r?"p-drawer-".concat(r):""]},root:function(t){var n=t.instance;return["p-drawer p-component",{"p-drawer-full":n.fullScreen}]},header:"p-drawer-header",title:"p-drawer-title",pcCloseButton:"p-drawer-close-button",content:"p-drawer-content",footer:"p-drawer-footer"},ce=K.extend({name:"drawer",style:se,classes:de,inlineStyles:le}),ue={name:"BaseDrawer",extends:V,props:{visible:{type:Boolean,default:!1},position:{type:String,default:"left"},header:{type:null,default:null},baseZIndex:{type:Number,default:0},autoZIndex:{type:Boolean,default:!0},dismissable:{type:Boolean,default:!0},showCloseIcon:{type:Boolean,default:!0},closeButtonProps:{type:Object,default:function(){return{severity:"secondary",text:!0,rounded:!0}}},closeIcon:{type:String,default:void 0},modal:{type:Boolean,default:!0},blockScroll:{type:Boolean,default:!1},closeOnEscape:{type:Boolean,default:!0}},style:ce,provide:function(){return{$pcDrawer:this,$parentInstance:this}}};function k(e){"@babel/helpers - typeof";return k=typeof Symbol=="function"&&typeof Symbol.iterator=="symbol"?function(t){return typeof t}:function(t){return t&&typeof Symbol=="function"&&t.constructor===Symbol&&t!==Symbol.prototype?"symbol":typeof t},k(e)}function B(e,t,n){return(t=fe(t))in e?Object.defineProperty(e,t,{value:n,enumerable:!0,configurable:!0,writable:!0}):e[t]=n,e}function fe(e){var t=pe(e,"string");return k(t)=="symbol"?t:t+""}function pe(e,t){if(k(e)!="object"||!e)return e;var n=e[Symbol.toPrimitive];if(n!==void 0){var a=n.call(e,t);if(k(a)!="object")return a;throw new TypeError("@@toPrimitive must return a primitive value.")}return(t==="string"?String:Number)(e)}var R={name:"Drawer",extends:ue,inheritAttrs:!1,emits:["update:visible","show","after-show","hide","after-hide","before-hide"],data:function(){return{containerVisible:this.visible}},container:null,mask:null,content:null,headerContainer:null,footerContainer:null,closeButton:null,outsideClickListener:null,documentKeydownListener:null,watch:{dismissable:function(t){t&&!this.modal?this.bindOutsideClickListener():this.unbindOutsideClickListener()}},updated:function(){this.visible&&(this.containerVisible=this.visible)},beforeUnmount:function(){this.disableDocumentSettings(),this.mask&&this.autoZIndex&&L.clear(this.mask),this.container=null,this.mask=null},methods:{hide:function(){this.$emit("update:visible",!1)},onEnter:function(){this.$emit("show"),this.focus(),this.bindDocumentKeyDownListener(),this.autoZIndex&&L.set("modal",this.mask,this.baseZIndex||this.$primevue.config.zIndex.modal)},onAfterEnter:function(){this.enableDocumentSettings(),this.$emit("after-show")},onBeforeLeave:function(){this.modal&&!this.isUnstyled&&U(this.mask,"p-overlay-mask-leave-active"),this.$emit("before-hide")},onLeave:function(){this.$emit("hide")},onAfterLeave:function(){this.autoZIndex&&L.clear(this.mask),this.unbindDocumentKeyDownListener(),this.containerVisible=!1,this.disableDocumentSettings(),this.$emit("after-hide")},onMaskClick:function(t){this.dismissable&&this.modal&&this.mask===t.target&&this.hide()},focus:function(){var t=function(l){return l&&l.querySelector("[autofocus]")},n=this.$slots.header&&t(this.headerContainer);n||(n=this.$slots.default&&t(this.container),n||(n=this.$slots.footer&&t(this.footerContainer),n||(n=this.closeButton))),n&&M(n)},enableDocumentSettings:function(){this.dismissable&&!this.modal&&this.bindOutsideClickListener(),this.blockScroll&&q()},disableDocumentSettings:function(){this.unbindOutsideClickListener(),this.blockScroll&&T()},onKeydown:function(t){t.code==="Escape"&&this.closeOnEscape&&this.hide()},containerRef:function(t){this.container=t},maskRef:function(t){this.mask=t},contentRef:function(t){this.content=t},headerContainerRef:function(t){this.headerContainer=t},footerContainerRef:function(t){this.footerContainer=t},closeButtonRef:function(t){this.closeButton=t?t.$el:void 0},bindDocumentKeyDownListener:function(){this.documentKeydownListener||(this.documentKeydownListener=this.onKeydown,document.addEventListener("keydown",this.documentKeydownListener))},unbindDocumentKeyDownListener:function(){this.documentKeydownListener&&(document.removeEventListener("keydown",this.documentKeydownListener),this.documentKeydownListener=null)},bindOutsideClickListener:function(){var t=this;this.outsideClickListener||(this.outsideClickListener=function(n){t.isOutsideClicked(n)&&t.hide()},document.addEventListener("click",this.outsideClickListener,!0))},unbindOutsideClickListener:function(){this.outsideClickListener&&(document.removeEventListener("click",this.outsideClickListener,!0),this.outsideClickListener=null)},isOutsideClicked:function(t){return this.container&&!this.container.contains(t.target)}},computed:{fullScreen:function(){return this.position==="full"},closeAriaLabel:function(){return this.$primevue.config.locale.aria?this.$primevue.config.locale.aria.close:void 0},dataP:function(){return j(B(B(B({"full-screen":this.position==="full"},this.position,this.position),"open",this.containerVisible),"modal",this.modal))}},directives:{focustrap:A},components:{Button:O,Portal:I,TimesIcon:z}},me=["data-p"],he=["role","aria-modal","data-p"];function we(e,t,n,a,l,r){var d=x("Button"),p=x("Portal"),b=Z("focustrap");return s(),C(p,null,{default:h(function(){return[l.containerVisible?(s(),f("div",c({key:0,ref:r.maskRef,onMousedown:t[0]||(t[0]=function(){return r.onMaskClick&&r.onMaskClick.apply(r,arguments)}),class:e.cx("mask"),style:e.sx("mask",!0,{position:e.position,modal:e.modal}),"data-p":r.dataP},e.ptm("mask")),[D(N,c({name:"p-drawer",onEnter:r.onEnter,onAfterEnter:r.onAfterEnter,onBeforeLeave:r.onBeforeLeave,onLeave:r.onLeave,onAfterLeave:r.onAfterLeave,appear:""},e.ptm("transition")),{default:h(function(){return[e.visible?F((s(),f("div",c({key:0,ref:r.containerRef,class:e.cx("root"),style:e.sx("root"),role:e.modal?"dialog":"complementary","aria-modal":e.modal?!0:void 0,"data-p":r.dataP},e.ptmi("root")),[e.$slots.container?u(e.$slots,"container",{key:0,closeCallback:r.hide}):(s(),f(Y,{key:1},[v("div",c({ref:r.headerContainerRef,class:e.cx("header")},e.ptm("header")),[u(e.$slots,"header",{class:E(e.cx("title"))},function(){return[e.header?(s(),f("div",c({key:0,class:e.cx("title")},e.ptm("title")),g(e.header),17)):m("",!0)]}),e.showCloseIcon?u(e.$slots,"closebutton",{key:0,closeCallback:r.hide},function(){return[D(d,c({ref:r.closeButtonRef,type:"button",class:e.cx("pcCloseButton"),"aria-label":r.closeAriaLabel,unstyled:e.unstyled,onClick:r.hide},e.closeButtonProps,{pt:e.ptm("pcCloseButton"),"data-pc-group-section":"iconcontainer"}),{icon:h(function(i){return[u(e.$slots,"closeicon",{},function(){return[(s(),C(Q(e.closeIcon?"span":"TimesIcon"),c({class:[e.closeIcon,i.class]},e.ptm("pcCloseButton").icon),null,16,["class"]))]})]}),_:3},16,["class","aria-label","unstyled","onClick","pt"])]}):m("",!0)],16),v("div",c({ref:r.contentRef,class:e.cx("content")},e.ptm("content")),[u(e.$slots,"default")],16),e.$slots.footer?(s(),f("div",c({key:0,ref:r.footerContainerRef,class:e.cx("footer")},e.ptm("footer")),[u(e.$slots,"footer")],16)):m("",!0)],64))],16,he)),[[b]]):m("",!0)]}),_:3},16,["onEnter","onAfterEnter","onBeforeLeave","onLeave","onAfterLeave"])],16,me)):m("",!0)]}),_:3})}R.render=we;function S(e){return new Promise(t=>{e.require({message:"You have unsaved changes. Discard them?",header:"Discard Changes",icon:"pi pi-exclamation-triangle",rejectProps:{label:"Keep Editing",severity:"secondary",outlined:!0},acceptProps:{label:"Discard",severity:"danger"},accept:()=>t(!0),reject:()=>t(!1)})})}function $e(e){const t=J(),n=G(),a=$(),l=e.stripQuery??["edit","from"];let r=!1;const d=W(()=>{const o=t.params[e.paramKey??"id"];return typeof o=="string"?o:void 0});function p(){const o={};for(const[w,y]of Object.entries(t.query))l.includes(w)||(o[w]=y);return o}function b(){r=!0,n.push({path:e.listPath,query:p()})}function i(o){r=!0,n.replace({path:`${e.listPath}/${o}`,query:p()})}return X(async()=>r||!e.dirty?.value?!0:S(a)),H(async(o,w)=>{const y=e.paramKey??"id";return o.params[y]===w.params[y]||r||!e.dirty?.value?!0:S(a)}),{id:d,goToList:b,replaceToDetail:i}}const ye={class:"entity-drawer-header"},ve={class:"entity-drawer-titles"},be={class:"entity-drawer-title"},ke={key:0,class:"entity-drawer-subtitle"},ge={key:0,class:"entity-drawer-header-extra"},Ce={key:0,class:"entity-drawer-state"},Le={class:"entity-drawer-footer"},Be=_({__name:"EntityDrawer",props:{title:{},subtitle:{default:void 0},size:{default:"default"},loading:{type:Boolean,default:!1},error:{default:null},dirty:{type:Boolean,default:!1}},emits:["close"],setup(e,{expose:t,emit:n}){const a=e,l=n,r=$(),d=ne(!1);ee(()=>{d.value=!0});async function p(i=!1){d.value&&(a.dirty&&!i&&!await S(r)||(d.value=!1))}function b(i){i||p()}return t({close:p}),(i,o)=>{const w=oe,y=ie,P=R;return s(),C(P,{visible:d.value,position:"right",modal:!1,"block-scroll":!1,dismissable:!1,class:E(["entity-drawer",e.size==="wide"?"entity-drawer-wide":"entity-drawer-default"]),"onUpdate:visible":b,onHide:o[0]||(o[0]=De=>l("close"))},te({header:h(()=>[v("div",ye,[v("div",ve,[v("h2",be,g(e.title),1),e.subtitle?(s(),f("p",ke,g(e.subtitle),1)):m("",!0)]),i.$slots["header-extra"]?(s(),f("div",ge,[u(i.$slots,"header-extra",{},void 0,!0)])):m("",!0)])]),default:h(()=>[e.loading?(s(),f("div",Ce,[D(w,{style:{width:"40px",height:"40px"}})])):e.error?(s(),C(y,{key:1,severity:"error",closable:!1},{default:h(()=>[re(g(e.error),1)]),_:1})):u(i.$slots,"default",{key:2},void 0,!0)]),_:2},[i.$slots.footer?{name:"footer",fn:h(()=>[v("div",Le,[u(i.$slots,"footer",{},void 0,!0)])]),key:"0"}:void 0]),1032,["visible","class"])}}}),Re=ae(Be,[["__scopeId","data-v-9b924863"]]);export{Re as E,$e as u};
