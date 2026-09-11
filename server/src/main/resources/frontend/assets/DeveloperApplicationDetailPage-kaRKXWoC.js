const __vite__mapDeps=(i,m=__vite__mapDeps,d=(m.f||(m.f=["assets/DeveloperApiDocsTab-ClBHI9tx.js","assets/index-CNuzEebD.js","assets/index-fHGdsFPK.css","assets/index-G-JbmQjy.js","assets/_commonjsHelpers-CqkleIqs.js","assets/developer-H637Ssrq.js","assets/DeveloperApiDocsTab-YD7EnUmi.css","assets/DeveloperEventTypesTab-CT5J_jlx.js","assets/index-DM1X00JJ.js","assets/index-ukUjgfYl.js","assets/index-CAiu7c1-.js","assets/index-BT0ztFIX.js","assets/index-jPVCqEya.js","assets/index-BpioN5Ra.js","assets/index-iMYGrcyd.js","assets/index-X34Ao0CO.js","assets/index-CiJA5xyU.js","assets/index-CzAILo7g.js","assets/index-CZNAC6sw.js","assets/index-B4_-DvdV.js","assets/index-DL4WAiA_.js","assets/index-BkfHNu0T.js","assets/schema-to-java-OCvQhVzh.js","assets/schema-highlight-DzEysrqi.js","assets/DeveloperEventTypesTab-Dmi1k4L0.css","assets/DeveloperProcessesTab-BWcwUTc5.js","assets/processes-DFhLIRH2.js","assets/DeveloperProcessesTab-DaAmZe5Y.css"])))=>i.map(i=>d[i]);
import{Y as w,o as r,g as v,af as h,m as d,a1 as k,ao as H,F as D,K as $,aW as it,j as _,l as p,L,p as m,U,$ as j,ag as M,az as V,aA as ot,am as O,ba as rt,a8 as B,as as lt,bb as F,ad as q,h as u,a6 as ct,b6 as P,i as dt,r as A,w as W,t as ut,q as l,n as T,y as I,u as bt,e as pt,z as vt,x as E,bc as K,aG as z,T as ht,_ as ft}from"./index-CNuzEebD.js";import{s as gt}from"./index-D9Lzfcyt.js";import{s as mt}from"./index-B4_-DvdV.js";import{s as yt}from"./index-BkfHNu0T.js";import{s as Tt}from"./index-G-JbmQjy.js";import{d as $t}from"./developer-H637Ssrq.js";var _t=`
    .p-tabs {
        display: flex;
        flex-direction: column;
    }

    .p-tablist {
        display: flex;
        position: relative;
        overflow: hidden;
        background: dt('tabs.tablist.background');
    }

    .p-tablist-viewport {
        overflow-x: auto;
        overflow-y: hidden;
        scroll-behavior: smooth;
        scrollbar-width: none;
        overscroll-behavior: contain auto;
    }

    .p-tablist-viewport::-webkit-scrollbar {
        display: none;
    }

    .p-tablist-tab-list {
        position: relative;
        display: flex;
        border-style: solid;
        border-color: dt('tabs.tablist.border.color');
        border-width: dt('tabs.tablist.border.width');
    }

    .p-tablist-content {
        flex-grow: 1;
    }

    .p-tablist-nav-button {
        all: unset;
        position: absolute !important;
        flex-shrink: 0;
        inset-block-start: 0;
        z-index: 2;
        height: 100%;
        display: flex;
        align-items: center;
        justify-content: center;
        background: dt('tabs.nav.button.background');
        color: dt('tabs.nav.button.color');
        width: dt('tabs.nav.button.width');
        transition:
            color dt('tabs.transition.duration'),
            outline-color dt('tabs.transition.duration'),
            box-shadow dt('tabs.transition.duration');
        box-shadow: dt('tabs.nav.button.shadow');
        outline-color: transparent;
        cursor: pointer;
    }

    .p-tablist-nav-button:focus-visible {
        z-index: 1;
        box-shadow: dt('tabs.nav.button.focus.ring.shadow');
        outline: dt('tabs.nav.button.focus.ring.width') dt('tabs.nav.button.focus.ring.style') dt('tabs.nav.button.focus.ring.color');
        outline-offset: dt('tabs.nav.button.focus.ring.offset');
    }

    .p-tablist-nav-button:hover {
        color: dt('tabs.nav.button.hover.color');
    }

    .p-tablist-prev-button {
        inset-inline-start: 0;
    }

    .p-tablist-next-button {
        inset-inline-end: 0;
    }

    .p-tablist-prev-button:dir(rtl),
    .p-tablist-next-button:dir(rtl) {
        transform: rotate(180deg);
    }

    .p-tab {
        flex-shrink: 0;
        cursor: pointer;
        user-select: none;
        position: relative;
        border-style: solid;
        white-space: nowrap;
        gap: dt('tabs.tab.gap');
        background: dt('tabs.tab.background');
        border-width: dt('tabs.tab.border.width');
        border-color: dt('tabs.tab.border.color');
        color: dt('tabs.tab.color');
        padding: dt('tabs.tab.padding');
        font-weight: dt('tabs.tab.font.weight');
        transition:
            background dt('tabs.transition.duration'),
            border-color dt('tabs.transition.duration'),
            color dt('tabs.transition.duration'),
            outline-color dt('tabs.transition.duration'),
            box-shadow dt('tabs.transition.duration');
        margin: dt('tabs.tab.margin');
        outline-color: transparent;
    }

    .p-tab:not(.p-disabled):focus-visible {
        z-index: 1;
        box-shadow: dt('tabs.tab.focus.ring.shadow');
        outline: dt('tabs.tab.focus.ring.width') dt('tabs.tab.focus.ring.style') dt('tabs.tab.focus.ring.color');
        outline-offset: dt('tabs.tab.focus.ring.offset');
    }

    .p-tab:not(.p-tab-active):not(.p-disabled):hover {
        background: dt('tabs.tab.hover.background');
        border-color: dt('tabs.tab.hover.border.color');
        color: dt('tabs.tab.hover.color');
    }

    .p-tab-active {
        background: dt('tabs.tab.active.background');
        border-color: dt('tabs.tab.active.border.color');
        color: dt('tabs.tab.active.color');
    }

    .p-tabpanels {
        background: dt('tabs.tabpanel.background');
        color: dt('tabs.tabpanel.color');
        padding: dt('tabs.tabpanel.padding');
        outline: 0 none;
    }

    .p-tabpanel:focus-visible {
        box-shadow: dt('tabs.tabpanel.focus.ring.shadow');
        outline: dt('tabs.tabpanel.focus.ring.width') dt('tabs.tabpanel.focus.ring.style') dt('tabs.tabpanel.focus.ring.color');
        outline-offset: dt('tabs.tabpanel.focus.ring.offset');
    }

    .p-tablist-active-bar {
        z-index: 1;
        display: block;
        position: absolute;
        inset-block-end: dt('tabs.active.bar.bottom');
        height: dt('tabs.active.bar.height');
        background: dt('tabs.active.bar.background');
        transition: 250ms cubic-bezier(0.35, 0, 0.25, 1);
    }
`,wt={root:function(t){var n=t.props;return["p-tabs p-component",{"p-tabs-scrollable":n.scrollable}]}},kt=w.extend({name:"tabs",style:_t,classes:wt}),xt={name:"BaseTabs",extends:k,props:{value:{type:[String,Number],default:void 0},lazy:{type:Boolean,default:!1},scrollable:{type:Boolean,default:!1},showNavigators:{type:Boolean,default:!0},tabindex:{type:Number,default:0},selectOnFocus:{type:Boolean,default:!1}},style:kt,provide:function(){return{$pcTabs:this,$parentInstance:this}}},G={name:"Tabs",extends:xt,inheritAttrs:!1,emits:["update:value"],data:function(){return{d_value:this.value}},watch:{value:function(t){this.d_value=t}},methods:{updateValue:function(t){this.d_value!==t&&(this.d_value=t,this.$emit("update:value",t))},isVertical:function(){return this.orientation==="vertical"}}};function Bt(e,t,n,s,i,a){return r(),v("div",d({class:e.cx("root")},e.ptmi("root")),[h(e.$slots,"default")],16)}G.render=Bt;var Pt={root:"p-tabpanels"},At=w.extend({name:"tabpanels",classes:Pt}),Lt={name:"BaseTabPanels",extends:k,props:{},style:At,provide:function(){return{$pcTabPanels:this,$parentInstance:this}}},Q={name:"TabPanels",extends:Lt,inheritAttrs:!1};function Ct(e,t,n,s,i,a){return r(),v("div",d({class:e.cx("root"),role:"presentation"},e.ptmi("root")),[h(e.$slots,"default")],16)}Q.render=Ct;var St={root:function(t){var n=t.instance;return["p-tabpanel",{"p-tabpanel-active":n.active}]}},Nt=w.extend({name:"tabpanel",classes:St}),Vt={name:"BaseTabPanel",extends:k,props:{value:{type:[String,Number],default:void 0},as:{type:[String,Object],default:"DIV"},asChild:{type:Boolean,default:!1},header:null,headerStyle:null,headerClass:null,headerProps:null,headerActionProps:null,contentStyle:null,contentClass:null,contentProps:null,disabled:Boolean},style:Nt,provide:function(){return{$pcTabPanel:this,$parentInstance:this}}},Y={name:"TabPanel",extends:Vt,inheritAttrs:!1,inject:["$pcTabs"],computed:{active:function(){var t;return H((t=this.$pcTabs)===null||t===void 0?void 0:t.d_value,this.value)},id:function(){var t;return"".concat((t=this.$pcTabs)===null||t===void 0?void 0:t.$id,"_tabpanel_").concat(this.value)},ariaLabelledby:function(){var t;return"".concat((t=this.$pcTabs)===null||t===void 0?void 0:t.$id,"_tab_").concat(this.value)},attrs:function(){return d(this.a11yAttrs,this.ptmi("root",this.ptParams))},a11yAttrs:function(){var t;return{id:this.id,tabindex:(t=this.$pcTabs)===null||t===void 0?void 0:t.tabindex,role:"tabpanel","aria-labelledby":this.ariaLabelledby,"data-pc-name":"tabpanel","data-p-active":this.active}},ptParams:function(){return{context:{active:this.active}}}}};function It(e,t,n,s,i,a){var o,b;return a.$pcTabs?(r(),v(D,{key:1},[e.asChild?h(e.$slots,"default",{key:1,class:U(e.cx("root")),active:a.active,a11yAttrs:a.a11yAttrs}):(r(),v(D,{key:0},[!((o=a.$pcTabs)!==null&&o!==void 0&&o.lazy)||a.active?$((r(),_(L(e.as),d({key:0,class:e.cx("root")},a.attrs),{default:p(function(){return[h(e.$slots,"default")]}),_:3},16,["class"])),[[it,(b=a.$pcTabs)!==null&&b!==void 0&&b.lazy?!0:a.active]]):m("",!0)],64))],64)):h(e.$slots,"default",{key:0})}Y.render=It;var Et={root:"p-tablist",content:"p-tablist-content p-tablist-viewport",tabList:"p-tablist-tab-list",activeBar:"p-tablist-active-bar",prevButton:"p-tablist-prev-button p-tablist-nav-button",nextButton:"p-tablist-next-button p-tablist-nav-button"},Kt=w.extend({name:"tablist",classes:Et}),zt={name:"BaseTabList",extends:k,props:{},style:Kt,provide:function(){return{$pcTabList:this,$parentInstance:this}}},J={name:"TabList",extends:zt,inheritAttrs:!1,inject:["$pcTabs"],data:function(){return{isPrevButtonEnabled:!1,isNextButtonEnabled:!0}},resizeObserver:void 0,watch:{showNavigators:function(t){t?this.bindResizeObserver():this.unbindResizeObserver()},activeValue:{flush:"post",handler:function(){this.updateInkBar()}}},mounted:function(){var t=this;setTimeout(function(){t.updateInkBar()},150),this.showNavigators&&(this.updateButtonState(),this.bindResizeObserver())},updated:function(){this.showNavigators&&this.updateButtonState()},beforeUnmount:function(){this.unbindResizeObserver()},methods:{onScroll:function(t){this.showNavigators&&this.updateButtonState(),t.preventDefault()},onPrevButtonClick:function(){var t=this.$refs.content,n=this.getVisibleButtonWidths(),s=V(t)-n,i=Math.abs(t.scrollLeft),a=s*.8,o=i-a,b=Math.max(o,0);t.scrollLeft=F(t)?-1*b:b},onNextButtonClick:function(){var t=this.$refs.content,n=this.getVisibleButtonWidths(),s=V(t)-n,i=Math.abs(t.scrollLeft),a=s*.8,o=i+a,b=t.scrollWidth-s,f=Math.min(o,b);t.scrollLeft=F(t)?-1*f:f},bindResizeObserver:function(){var t=this;this.resizeObserver=new ResizeObserver(function(){return t.updateButtonState()}),this.resizeObserver.observe(this.$refs.list)},unbindResizeObserver:function(){var t;(t=this.resizeObserver)===null||t===void 0||t.unobserve(this.$refs.list),this.resizeObserver=void 0},updateInkBar:function(){var t=this.$refs,n=t.content,s=t.inkbar,i=t.tabs;if(s){var a=O(n,'[data-pc-name="tab"][data-p-active="true"]');this.$pcTabs.isVertical()?(s.style.height=rt(a)+"px",s.style.top=B(a).top-B(i).top+"px"):(s.style.width=lt(a)+"px",s.style.left=B(a).left-B(i).left+"px")}},updateButtonState:function(){var t=this.$refs,n=t.list,s=t.content,i=s.scrollTop,a=s.scrollWidth,o=s.scrollHeight,b=s.offsetWidth,f=s.offsetHeight,x=Math.abs(s.scrollLeft),y=[V(s),ot(s)],g=y[0],c=y[1];this.$pcTabs.isVertical()?(this.isPrevButtonEnabled=i!==0,this.isNextButtonEnabled=n.offsetHeight>=f&&parseInt(i)!==o-c):(this.isPrevButtonEnabled=x!==0,this.isNextButtonEnabled=n.offsetWidth>=b&&parseInt(x)!==a-g)},getVisibleButtonWidths:function(){var t=this.$refs,n=t.prevButton,s=t.nextButton,i=0;return this.showNavigators&&(i=(n?.offsetWidth||0)+(s?.offsetWidth||0)),i}},computed:{templates:function(){return this.$pcTabs.$slots},activeValue:function(){return this.$pcTabs.d_value},showNavigators:function(){return this.$pcTabs.showNavigators},prevButtonAriaLabel:function(){return this.$primevue.config.locale.aria?this.$primevue.config.locale.aria.previous:void 0},nextButtonAriaLabel:function(){return this.$primevue.config.locale.aria?this.$primevue.config.locale.aria.next:void 0},dataP:function(){return M({scrollable:this.$pcTabs.scrollable})}},components:{ChevronLeftIcon:gt,ChevronRightIcon:mt},directives:{ripple:j}},Dt=["data-p"],Ot=["aria-label","tabindex"],Rt=["data-p"],Ft=["aria-orientation"],Wt=["aria-label","tabindex"];function Ht(e,t,n,s,i,a){var o=q("ripple");return r(),v("div",d({ref:"list",class:e.cx("root"),"data-p":a.dataP},e.ptmi("root")),[a.showNavigators&&i.isPrevButtonEnabled?$((r(),v("button",d({key:0,ref:"prevButton",type:"button",class:e.cx("prevButton"),"aria-label":a.prevButtonAriaLabel,tabindex:a.$pcTabs.tabindex,onClick:t[0]||(t[0]=function(){return a.onPrevButtonClick&&a.onPrevButtonClick.apply(a,arguments)})},e.ptm("prevButton"),{"data-pc-group-section":"navigator"}),[(r(),_(L(a.templates.previcon||"ChevronLeftIcon"),d({"aria-hidden":"true"},e.ptm("prevIcon")),null,16))],16,Ot)),[[o]]):m("",!0),u("div",d({ref:"content",class:e.cx("content"),onScroll:t[1]||(t[1]=function(){return a.onScroll&&a.onScroll.apply(a,arguments)}),"data-p":a.dataP},e.ptm("content")),[u("div",d({ref:"tabs",class:e.cx("tabList"),role:"tablist","aria-orientation":a.$pcTabs.orientation||"horizontal"},e.ptm("tabList")),[h(e.$slots,"default"),u("span",d({ref:"inkbar",class:e.cx("activeBar"),role:"presentation","aria-hidden":"true"},e.ptm("activeBar")),null,16)],16,Ft)],16,Rt),a.showNavigators&&i.isNextButtonEnabled?$((r(),v("button",d({key:1,ref:"nextButton",type:"button",class:e.cx("nextButton"),"aria-label":a.nextButtonAriaLabel,tabindex:a.$pcTabs.tabindex,onClick:t[2]||(t[2]=function(){return a.onNextButtonClick&&a.onNextButtonClick.apply(a,arguments)})},e.ptm("nextButton"),{"data-pc-group-section":"navigator"}),[(r(),_(L(a.templates.nexticon||"ChevronRightIcon"),d({"aria-hidden":"true"},e.ptm("nextIcon")),null,16))],16,Wt)),[[o]]):m("",!0)],16,Dt)}J.render=Ht;var Ut={root:function(t){var n=t.instance,s=t.props;return["p-tab",{"p-tab-active":n.active,"p-disabled":s.disabled}]}},jt=w.extend({name:"tab",classes:Ut}),Mt={name:"BaseTab",extends:k,props:{value:{type:[String,Number],default:void 0},disabled:{type:Boolean,default:!1},as:{type:[String,Object],default:"BUTTON"},asChild:{type:Boolean,default:!1}},style:jt,provide:function(){return{$pcTab:this,$parentInstance:this}}},X={name:"Tab",extends:Mt,inheritAttrs:!1,inject:["$pcTabs","$pcTabList"],methods:{onFocus:function(){this.$pcTabs.selectOnFocus&&this.changeActiveValue()},onClick:function(){this.changeActiveValue()},onKeydown:function(t){switch(t.code){case"ArrowRight":this.onArrowRightKey(t);break;case"ArrowLeft":this.onArrowLeftKey(t);break;case"Home":this.onHomeKey(t);break;case"End":this.onEndKey(t);break;case"PageDown":this.onPageDownKey(t);break;case"PageUp":this.onPageUpKey(t);break;case"Enter":case"NumpadEnter":case"Space":this.onEnterKey(t);break}},onArrowRightKey:function(t){var n=this.findNextTab(t.currentTarget);n?this.changeFocusedTab(t,n):this.onHomeKey(t),t.preventDefault()},onArrowLeftKey:function(t){var n=this.findPrevTab(t.currentTarget);n?this.changeFocusedTab(t,n):this.onEndKey(t),t.preventDefault()},onHomeKey:function(t){var n=this.findFirstTab();this.changeFocusedTab(t,n),t.preventDefault()},onEndKey:function(t){var n=this.findLastTab();this.changeFocusedTab(t,n),t.preventDefault()},onPageDownKey:function(t){this.scrollInView(this.findLastTab()),t.preventDefault()},onPageUpKey:function(t){this.scrollInView(this.findFirstTab()),t.preventDefault()},onEnterKey:function(t){this.changeActiveValue()},findNextTab:function(t){var n=arguments.length>1&&arguments[1]!==void 0?arguments[1]:!1,s=n?t:t.nextElementSibling;return s?P(s,"data-p-disabled")||P(s,"data-pc-section")==="activebar"?this.findNextTab(s):O(s,'[data-pc-name="tab"]'):null},findPrevTab:function(t){var n=arguments.length>1&&arguments[1]!==void 0?arguments[1]:!1,s=n?t:t.previousElementSibling;return s?P(s,"data-p-disabled")||P(s,"data-pc-section")==="activebar"?this.findPrevTab(s):O(s,'[data-pc-name="tab"]'):null},findFirstTab:function(){return this.findNextTab(this.$pcTabList.$refs.tabs.firstElementChild,!0)},findLastTab:function(){return this.findPrevTab(this.$pcTabList.$refs.tabs.lastElementChild,!0)},changeActiveValue:function(){this.$pcTabs.updateValue(this.value)},changeFocusedTab:function(t,n){ct(n),this.scrollInView(n)},scrollInView:function(t){var n;t==null||(n=t.scrollIntoView)===null||n===void 0||n.call(t,{block:"nearest"})}},computed:{active:function(){var t;return H((t=this.$pcTabs)===null||t===void 0?void 0:t.d_value,this.value)},id:function(){var t;return"".concat((t=this.$pcTabs)===null||t===void 0?void 0:t.$id,"_tab_").concat(this.value)},ariaControls:function(){var t;return"".concat((t=this.$pcTabs)===null||t===void 0?void 0:t.$id,"_tabpanel_").concat(this.value)},attrs:function(){return d(this.asAttrs,this.a11yAttrs,this.ptmi("root",this.ptParams))},asAttrs:function(){return this.as==="BUTTON"?{type:"button",disabled:this.disabled}:void 0},a11yAttrs:function(){return{id:this.id,tabindex:this.active?this.$pcTabs.tabindex:-1,role:"tab","aria-selected":this.active,"aria-controls":this.ariaControls,"data-pc-name":"tab","data-p-disabled":this.disabled,"data-p-active":this.active,onFocus:this.onFocus,onKeydown:this.onKeydown}},ptParams:function(){return{context:{active:this.active}}},dataP:function(){return M({active:this.active})}},directives:{ripple:j}};function qt(e,t,n,s,i,a){var o=q("ripple");return e.asChild?h(e.$slots,"default",{key:1,dataP:a.dataP,class:U(e.cx("root")),active:a.active,a11yAttrs:a.a11yAttrs,onClick:a.onClick}):$((r(),_(L(e.as),d({key:0,class:e.cx("root"),"data-p":a.dataP,onClick:a.onClick},a.attrs),{default:p(function(){return[h(e.$slots,"default")]}),_:3},16,["class","data-p","onClick"])),[[o]])}X.render=qt;const Gt={class:"page-container"},Qt={key:0,class:"loading-container"},Yt={class:"page-header"},Jt={class:"header-content"},Xt={class:"header-text"},Zt={class:"page-title"},te={class:"page-subtitle"},ee={class:"app-code"},ae={key:0},ne={class:"header-actions"},se=dt({__name:"DeveloperApplicationDetailPage",setup(e){const t=bt(),n=pt(),s=A(String(t.params.id)),i=A(null),a=A(!0),o=A(typeof t.query.tab=="string"?t.query.tab:"api-docs"),b=K(()=>z(()=>import("./DeveloperApiDocsTab-ClBHI9tx.js"),__vite__mapDeps([0,1,2,3,4,5,6]))),f=K(()=>z(()=>import("./DeveloperEventTypesTab-CT5J_jlx.js"),__vite__mapDeps([7,8,1,2,9,10,11,12,13,14,15,16,17,18,19,20,21,5,22,23,24]))),x=K(()=>z(()=>import("./DeveloperProcessesTab-BWcwUTc5.js"),__vite__mapDeps([25,1,2,8,9,10,11,12,13,14,15,16,17,18,19,20,21,26,27])));async function y(){a.value=!0;try{i.value=await $t.getApplication(s.value)}finally{a.value=!1}}return W(()=>t.params.id,g=>{s.value=String(g),y()}),W(o,g=>{n.replace({query:{...t.query,tab:g}})}),ut(y),(g,c)=>{const Z=Tt,R=vt,tt=yt,C=X,et=J,S=Y,at=Q,nt=G,st=ht;return r(),v("div",Gt,[a.value?(r(),v("div",Qt,[l(Z,{strokeWidth:"3"})])):i.value?(r(),v(D,{key:1},[u("header",Yt,[u("div",Jt,[$(l(R,{icon:"pi pi-arrow-left",text:"",severity:"secondary",onClick:c[0]||(c[0]=N=>T(n).push("/developer"))},null,512),[[st,"Back to applications"]]),u("div",Xt,[u("h1",Zt,I(i.value.name),1),u("p",te,[u("span",ee,I(i.value.code),1),i.value.description?(r(),v("span",ae," · "+I(i.value.description),1)):m("",!0)])]),i.value.currentVersion?(r(),_(tt,{key:0,value:`API v${i.value.currentVersion}`,severity:"info"},null,8,["value"])):m("",!0)]),u("div",ne,[l(R,{label:"Versions",icon:"pi pi-history",severity:"secondary",outlined:"",onClick:c[1]||(c[1]=N=>T(n).push(`/developer/applications/${s.value}/versions`))})])]),l(nt,{value:o.value,"onUpdate:value":c[2]||(c[2]=N=>o.value=N),class:"developer-tabs"},{default:p(()=>[l(et,null,{default:p(()=>[l(C,{value:"api-docs"},{default:p(()=>[...c[3]||(c[3]=[u("i",{class:"pi pi-book"},null,-1),E(" API Docs",-1)])]),_:1}),l(C,{value:"event-types"},{default:p(()=>[...c[4]||(c[4]=[u("i",{class:"pi pi-bolt"},null,-1),E(" Event Types",-1)])]),_:1}),l(C,{value:"processes"},{default:p(()=>[...c[5]||(c[5]=[u("i",{class:"pi pi-sitemap"},null,-1),E(" Processes",-1)])]),_:1})]),_:1}),l(at,null,{default:p(()=>[l(S,{value:"api-docs"},{default:p(()=>[l(T(b),{"application-id":s.value},null,8,["application-id"])]),_:1}),l(S,{value:"event-types"},{default:p(()=>[l(T(f),{"application-id":s.value},null,8,["application-id"])]),_:1}),l(S,{value:"processes"},{default:p(()=>[l(T(x),{"application-code":i.value.code},null,8,["application-code"])]),_:1})]),_:1})]),_:1},8,["value"])],64)):m("",!0)])}}}),ue=ft(se,[["__scopeId","data-v-077d4dcd"]]);export{ue as default};
