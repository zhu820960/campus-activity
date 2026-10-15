import {
	createSSRApp
} from "vue";
import App from "./App.vue";
import "./common/theme.css";
export function createApp() {
	const app = createSSRApp(App);
	return {
		app,
	};
}
