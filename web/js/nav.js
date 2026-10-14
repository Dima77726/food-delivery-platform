// Навигация без зависимостей: на неё ссылаются и каркас, и страницы.

export const navigate = (path) => { location.hash = `#${path}`; };

const HOME_BY_ROLE = { CUSTOMER: "/", RESTAURANT_OWNER: "/owner", COURIER: "/courier", ADMIN: "/admin" };
export const homeFor = (role) => HOME_BY_ROLE[role] || "/";
