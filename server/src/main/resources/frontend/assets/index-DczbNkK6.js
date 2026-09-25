import{a0 as b,a4 as p,o as l,g as u,ai as d,m as o,ar as C,F as P,O as g,b0 as O,j as m,l as A,P as y,p as w,X as S,a2 as N,aj as K,aC as $,aD as E,ap as B,bd as F,ab as h,av as R,be as L,ag as V,h as T,a9 as _,bb as f}from"./index-BHtQDKqt.js";import{s as D}from"./index-CLlGgVXp.js";import{s as W}from"./index-BU2mLZz0.js";var H=`
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
`,j={root:function(t){var n=t.props;return["p-tabs p-component",{"p-tabs-scrollable":n.scrollable}]}},U=b.extend({name:"tabs",style:H,classes:j}),M={name:"BaseTabs",extends:p,props:{value:{type:[String,Number],default:void 0},lazy:{type:Boolean,default:!1},scrollable:{type:Boolean,default:!1},showNavigators:{type:Boolean,default:!0},tabindex:{type:Number,default:0},selectOnFocus:{type:Boolean,default:!1}},style:U,provide:function(){return{$pcTabs:this,$parentInstance:this}}},Q={name:"Tabs",extends:M,inheritAttrs:!1,emits:["update:value"],data:function(){return{d_value:this.value}},watch:{value:function(t){this.d_value=t}},methods:{updateValue:function(t){this.d_value!==t&&(this.d_value=t,this.$emit("update:value",t))},isVertical:function(){return this.orientation==="vertical"}}};function X(a,t,n,s,i,e){return l(),u("div",o({class:a.cx("root")},a.ptmi("root")),[d(a.$slots,"default")],16)}Q.render=X;var q={root:"p-tabpanels"},G=b.extend({name:"tabpanels",classes:q}),J={name:"BaseTabPanels",extends:p,props:{},style:G,provide:function(){return{$pcTabPanels:this,$parentInstance:this}}},Y={name:"TabPanels",extends:J,inheritAttrs:!1};function Z(a,t,n,s,i,e){return l(),u("div",o({class:a.cx("root"),role:"presentation"},a.ptmi("root")),[d(a.$slots,"default")],16)}Y.render=Z;var tt={root:function(t){var n=t.instance;return["p-tabpanel",{"p-tabpanel-active":n.active}]}},at=b.extend({name:"tabpanel",classes:tt}),et={name:"BaseTabPanel",extends:p,props:{value:{type:[String,Number],default:void 0},as:{type:[String,Object],default:"DIV"},asChild:{type:Boolean,default:!1},header:null,headerStyle:null,headerClass:null,headerProps:null,headerActionProps:null,contentStyle:null,contentClass:null,contentProps:null,disabled:Boolean},style:at,provide:function(){return{$pcTabPanel:this,$parentInstance:this}}},nt={name:"TabPanel",extends:et,inheritAttrs:!1,inject:["$pcTabs"],computed:{active:function(){var t;return C((t=this.$pcTabs)===null||t===void 0?void 0:t.d_value,this.value)},id:function(){var t;return"".concat((t=this.$pcTabs)===null||t===void 0?void 0:t.$id,"_tabpanel_").concat(this.value)},ariaLabelledby:function(){var t;return"".concat((t=this.$pcTabs)===null||t===void 0?void 0:t.$id,"_tab_").concat(this.value)},attrs:function(){return o(this.a11yAttrs,this.ptmi("root",this.ptParams))},a11yAttrs:function(){var t;return{id:this.id,tabindex:(t=this.$pcTabs)===null||t===void 0?void 0:t.tabindex,role:"tabpanel","aria-labelledby":this.ariaLabelledby,"data-pc-name":"tabpanel","data-p-active":this.active}},ptParams:function(){return{context:{active:this.active}}}}};function st(a,t,n,s,i,e){var r,c;return e.$pcTabs?(l(),u(P,{key:1},[a.asChild?d(a.$slots,"default",{key:1,class:S(a.cx("root")),active:e.active,a11yAttrs:e.a11yAttrs}):(l(),u(P,{key:0},[!((r=e.$pcTabs)!==null&&r!==void 0&&r.lazy)||e.active?g((l(),m(y(a.as),o({key:0,class:a.cx("root")},e.attrs),{default:A(function(){return[d(a.$slots,"default")]}),_:3},16,["class"])),[[O,(c=e.$pcTabs)!==null&&c!==void 0&&c.lazy?!0:e.active]]):w("",!0)],64))],64)):d(a.$slots,"default",{key:0})}nt.render=st;var it={root:"p-tablist",content:"p-tablist-content p-tablist-viewport",tabList:"p-tablist-tab-list",activeBar:"p-tablist-active-bar",prevButton:"p-tablist-prev-button p-tablist-nav-button",nextButton:"p-tablist-next-button p-tablist-nav-button"},rt=b.extend({name:"tablist",classes:it}),ot={name:"BaseTabList",extends:p,props:{},style:rt,provide:function(){return{$pcTabList:this,$parentInstance:this}}},lt={name:"TabList",extends:ot,inheritAttrs:!1,inject:["$pcTabs"],data:function(){return{isPrevButtonEnabled:!1,isNextButtonEnabled:!0}},resizeObserver:void 0,watch:{showNavigators:function(t){t?this.bindResizeObserver():this.unbindResizeObserver()},activeValue:{flush:"post",handler:function(){this.updateInkBar()}}},mounted:function(){var t=this;setTimeout(function(){t.updateInkBar()},150),this.showNavigators&&(this.updateButtonState(),this.bindResizeObserver())},updated:function(){this.showNavigators&&this.updateButtonState()},beforeUnmount:function(){this.unbindResizeObserver()},methods:{onScroll:function(t){this.showNavigators&&this.updateButtonState(),t.preventDefault()},onPrevButtonClick:function(){var t=this.$refs.content,n=this.getVisibleButtonWidths(),s=$(t)-n,i=Math.abs(t.scrollLeft),e=s*.8,r=i-e,c=Math.max(r,0);t.scrollLeft=L(t)?-1*c:c},onNextButtonClick:function(){var t=this.$refs.content,n=this.getVisibleButtonWidths(),s=$(t)-n,i=Math.abs(t.scrollLeft),e=s*.8,r=i+e,c=t.scrollWidth-s,v=Math.min(r,c);t.scrollLeft=L(t)?-1*v:v},bindResizeObserver:function(){var t=this;this.resizeObserver=new ResizeObserver(function(){return t.updateButtonState()}),this.resizeObserver.observe(this.$refs.list)},unbindResizeObserver:function(){var t;(t=this.resizeObserver)===null||t===void 0||t.unobserve(this.$refs.list),this.resizeObserver=void 0},updateInkBar:function(){var t=this.$refs,n=t.content,s=t.inkbar,i=t.tabs;if(s){var e=B(n,'[data-pc-name="tab"][data-p-active="true"]');this.$pcTabs.isVertical()?(s.style.height=F(e)+"px",s.style.top=h(e).top-h(i).top+"px"):(s.style.width=R(e)+"px",s.style.left=h(e).left-h(i).left+"px")}},updateButtonState:function(){var t=this.$refs,n=t.list,s=t.content,i=s.scrollTop,e=s.scrollWidth,r=s.scrollHeight,c=s.offsetWidth,v=s.offsetHeight,k=Math.abs(s.scrollLeft),x=[$(s),E(s)],z=x[0],I=x[1];this.$pcTabs.isVertical()?(this.isPrevButtonEnabled=i!==0,this.isNextButtonEnabled=n.offsetHeight>=v&&parseInt(i)!==r-I):(this.isPrevButtonEnabled=k!==0,this.isNextButtonEnabled=n.offsetWidth>=c&&parseInt(k)!==e-z)},getVisibleButtonWidths:function(){var t=this.$refs,n=t.prevButton,s=t.nextButton,i=0;return this.showNavigators&&(i=(n?.offsetWidth||0)+(s?.offsetWidth||0)),i}},computed:{templates:function(){return this.$pcTabs.$slots},activeValue:function(){return this.$pcTabs.d_value},showNavigators:function(){return this.$pcTabs.showNavigators},prevButtonAriaLabel:function(){return this.$primevue.config.locale.aria?this.$primevue.config.locale.aria.previous:void 0},nextButtonAriaLabel:function(){return this.$primevue.config.locale.aria?this.$primevue.config.locale.aria.next:void 0},dataP:function(){return K({scrollable:this.$pcTabs.scrollable})}},components:{ChevronLeftIcon:D,ChevronRightIcon:W},directives:{ripple:N}},ct=["data-p"],dt=["aria-label","tabindex"],ut=["data-p"],bt=["aria-orientation"],pt=["aria-label","tabindex"];function vt(a,t,n,s,i,e){var r=V("ripple");return l(),u("div",o({ref:"list",class:a.cx("root"),"data-p":e.dataP},a.ptmi("root")),[e.showNavigators&&i.isPrevButtonEnabled?g((l(),u("button",o({key:0,ref:"prevButton",type:"button",class:a.cx("prevButton"),"aria-label":e.prevButtonAriaLabel,tabindex:e.$pcTabs.tabindex,onClick:t[0]||(t[0]=function(){return e.onPrevButtonClick&&e.onPrevButtonClick.apply(e,arguments)})},a.ptm("prevButton"),{"data-pc-group-section":"navigator"}),[(l(),m(y(e.templates.previcon||"ChevronLeftIcon"),o({"aria-hidden":"true"},a.ptm("prevIcon")),null,16))],16,dt)),[[r]]):w("",!0),T("div",o({ref:"content",class:a.cx("content"),onScroll:t[1]||(t[1]=function(){return e.onScroll&&e.onScroll.apply(e,arguments)}),"data-p":e.dataP},a.ptm("content")),[T("div",o({ref:"tabs",class:a.cx("tabList"),role:"tablist","aria-orientation":e.$pcTabs.orientation||"horizontal"},a.ptm("tabList")),[d(a.$slots,"default"),T("span",o({ref:"inkbar",class:a.cx("activeBar"),role:"presentation","aria-hidden":"true"},a.ptm("activeBar")),null,16)],16,bt)],16,ut),e.showNavigators&&i.isNextButtonEnabled?g((l(),u("button",o({key:1,ref:"nextButton",type:"button",class:a.cx("nextButton"),"aria-label":e.nextButtonAriaLabel,tabindex:e.$pcTabs.tabindex,onClick:t[2]||(t[2]=function(){return e.onNextButtonClick&&e.onNextButtonClick.apply(e,arguments)})},a.ptm("nextButton"),{"data-pc-group-section":"navigator"}),[(l(),m(y(e.templates.nexticon||"ChevronRightIcon"),o({"aria-hidden":"true"},a.ptm("nextIcon")),null,16))],16,pt)),[[r]]):w("",!0)],16,ct)}lt.render=vt;var ht={root:function(t){var n=t.instance,s=t.props;return["p-tab",{"p-tab-active":n.active,"p-disabled":s.disabled}]}},ft=b.extend({name:"tab",classes:ht}),gt={name:"BaseTab",extends:p,props:{value:{type:[String,Number],default:void 0},disabled:{type:Boolean,default:!1},as:{type:[String,Object],default:"BUTTON"},asChild:{type:Boolean,default:!1}},style:ft,provide:function(){return{$pcTab:this,$parentInstance:this}}},mt={name:"Tab",extends:gt,inheritAttrs:!1,inject:["$pcTabs","$pcTabList"],methods:{onFocus:function(){this.$pcTabs.selectOnFocus&&this.changeActiveValue()},onClick:function(){this.changeActiveValue()},onKeydown:function(t){switch(t.code){case"ArrowRight":this.onArrowRightKey(t);break;case"ArrowLeft":this.onArrowLeftKey(t);break;case"Home":this.onHomeKey(t);break;case"End":this.onEndKey(t);break;case"PageDown":this.onPageDownKey(t);break;case"PageUp":this.onPageUpKey(t);break;case"Enter":case"NumpadEnter":case"Space":this.onEnterKey(t);break}},onArrowRightKey:function(t){var n=this.findNextTab(t.currentTarget);n?this.changeFocusedTab(t,n):this.onHomeKey(t),t.preventDefault()},onArrowLeftKey:function(t){var n=this.findPrevTab(t.currentTarget);n?this.changeFocusedTab(t,n):this.onEndKey(t),t.preventDefault()},onHomeKey:function(t){var n=this.findFirstTab();this.changeFocusedTab(t,n),t.preventDefault()},onEndKey:function(t){var n=this.findLastTab();this.changeFocusedTab(t,n),t.preventDefault()},onPageDownKey:function(t){this.scrollInView(this.findLastTab()),t.preventDefault()},onPageUpKey:function(t){this.scrollInView(this.findFirstTab()),t.preventDefault()},onEnterKey:function(t){this.changeActiveValue()},findNextTab:function(t){var n=arguments.length>1&&arguments[1]!==void 0?arguments[1]:!1,s=n?t:t.nextElementSibling;return s?f(s,"data-p-disabled")||f(s,"data-pc-section")==="activebar"?this.findNextTab(s):B(s,'[data-pc-name="tab"]'):null},findPrevTab:function(t){var n=arguments.length>1&&arguments[1]!==void 0?arguments[1]:!1,s=n?t:t.previousElementSibling;return s?f(s,"data-p-disabled")||f(s,"data-pc-section")==="activebar"?this.findPrevTab(s):B(s,'[data-pc-name="tab"]'):null},findFirstTab:function(){return this.findNextTab(this.$pcTabList.$refs.tabs.firstElementChild,!0)},findLastTab:function(){return this.findPrevTab(this.$pcTabList.$refs.tabs.lastElementChild,!0)},changeActiveValue:function(){this.$pcTabs.updateValue(this.value)},changeFocusedTab:function(t,n){_(n),this.scrollInView(n)},scrollInView:function(t){var n;t==null||(n=t.scrollIntoView)===null||n===void 0||n.call(t,{block:"nearest"})}},computed:{active:function(){var t;return C((t=this.$pcTabs)===null||t===void 0?void 0:t.d_value,this.value)},id:function(){var t;return"".concat((t=this.$pcTabs)===null||t===void 0?void 0:t.$id,"_tab_").concat(this.value)},ariaControls:function(){var t;return"".concat((t=this.$pcTabs)===null||t===void 0?void 0:t.$id,"_tabpanel_").concat(this.value)},attrs:function(){return o(this.asAttrs,this.a11yAttrs,this.ptmi("root",this.ptParams))},asAttrs:function(){return this.as==="BUTTON"?{type:"button",disabled:this.disabled}:void 0},a11yAttrs:function(){return{id:this.id,tabindex:this.active?this.$pcTabs.tabindex:-1,role:"tab","aria-selected":this.active,"aria-controls":this.ariaControls,"data-pc-name":"tab","data-p-disabled":this.disabled,"data-p-active":this.active,onFocus:this.onFocus,onKeydown:this.onKeydown}},ptParams:function(){return{context:{active:this.active}}},dataP:function(){return K({active:this.active})}},directives:{ripple:N}};function yt(a,t,n,s,i,e){var r=V("ripple");return a.asChild?d(a.$slots,"default",{key:1,dataP:e.dataP,class:S(a.cx("root")),active:e.active,a11yAttrs:e.a11yAttrs,onClick:e.onClick}):g((l(),m(y(a.as),o({key:0,class:a.cx("root"),"data-p":e.dataP,onClick:e.onClick},e.attrs),{default:A(function(){return[d(a.$slots,"default")]}),_:3},16,["class","data-p","onClick"])),[[r]])}mt.render=yt;export{mt as a,Y as b,nt as c,Q as d,lt as s};
