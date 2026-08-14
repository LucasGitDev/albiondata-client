export namespace main {
	
	export class SettingsPayload {
	    publicIngestBaseUrls: string;
	    privateIngestBaseUrls: string;
	
	    static createFrom(source: any = {}) {
	        return new SettingsPayload(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.publicIngestBaseUrls = source["publicIngestBaseUrls"];
	        this.privateIngestBaseUrls = source["privateIngestBaseUrls"];
	    }
	}

}

